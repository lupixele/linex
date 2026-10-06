/* Private bridge boundary; this core neither opens sockets nor runs a resolver. */
#include "linex_dns.h"
#include <limits.h>
#include <stdlib.h>
#include <string.h>

enum { REQUEST = 1, ANSWER = 2, ERROR = 3, CANCEL = 4, UDP = 1, TCP = 2,
       OK = 0, UNSUPPORTED = 2, TIMEOUT = 4, STOPPED = 7 };
typedef struct {
    bool active;
    uint64_t id, connection, deadline;
    uint8_t transport;
    LinexDnsPeer peer;
    uint16_t wire_id, flags, udp_limit;
    size_t question_size;
    uint8_t question[260];
} Pending;
typedef struct {
    bool active;
    uint64_t id, deadline;
    size_t used, wanted;
    uint8_t input[LINEX_DNS_QUERY_MAX + 2];
} Connection;
typedef struct Response {
    struct Response *next;
    uint64_t connection, deadline;
    size_t size, offset, charge;
    uint8_t bytes[];
} Response;
struct LinexDns {
    uint64_t generation, next_id;
    bool in_callback;
    LinexDnsCallbacks cb;
    Pending pending[LINEX_DNS_PENDING_MAX];
    Connection connections[LINEX_DNS_CONNECTION_MAX];
    Response *responses;
    size_t queued;
};
static uint16_t be16(const uint8_t *p) { return (uint16_t)((uint16_t)p[0] << 8 | p[1]); }
static uint32_t be32(const uint8_t *p) { return (uint32_t)p[0]<<24 | (uint32_t)p[1]<<16 | (uint32_t)p[2]<<8 | p[3]; }
static uint64_t be64(const uint8_t *p) { return (uint64_t)be32(p)<<32 | be32(p+4); }
static void put16(uint8_t *p, uint16_t v) { p[0]=(uint8_t)(v>>8); p[1]=(uint8_t)v; }
static void put32(uint8_t *p, uint32_t v) { p[0]=(uint8_t)(v>>24); p[1]=(uint8_t)(v>>16); p[2]=(uint8_t)(v>>8); p[3]=(uint8_t)v; }
static void put64(uint8_t *p, uint64_t v) { put32(p,(uint32_t)(v>>32)); put32(p+4,(uint32_t)v); }
static uint64_t now(LinexDns *d) {
    d->in_callback=true;
    uint64_t value=d->cb.now_ms(d->cb.opaque);
    d->in_callback=false;
    return value;
}
static uint64_t deadline(LinexDns *d) { uint64_t t=now(d); return t>UINT64_MAX-LINEX_DNS_DEADLINE_MS ? UINT64_MAX : t+LINEX_DNS_DEADLINE_MS; }
static Connection *connection(LinexDns *d, uint64_t id) {
    for (size_t i=0;i<LINEX_DNS_CONNECTION_MAX;i++) if(d->connections[i].active && d->connections[i].id==id) return &d->connections[i];
    return NULL;
}
/* Decode a bounded DNS name, including compressed names. Canonical output
 * makes failure/truncated replies independent of pointers into removed records. */
static bool name(const uint8_t *w,size_t n,size_t start,size_t *end,uint8_t *out,size_t *used,bool compressed) {
    size_t p=start,u=0,e=0,steps=0; bool jumped=false;
    while(p<n && ++steps<=128) {
        size_t opcode=p;
        uint8_t v=w[p++];
        if((v&0xc0)==0xc0) {
            if(p>=n) return false;
            size_t target=(size_t)(v&0x3f)<<8 | w[p++];
            if(!compressed || target<12 || target>=opcode) return false;
            if(!jumped) { e=p; jumped=true; } p=target; continue;
        }
        if(v&0xc0 || v>63 || p+v>n || u+1+v>255) return false;
        if(out) { out[u]=v; if(v) memcpy(out+u+1,w+p,v); }
        u+=1+v; p+=v;
        if(!v) { *end=jumped?e:p; if(used) *used=u; return true; }
    }
    return false;
}
static bool parse_question(const uint8_t *w,size_t n,Pending *q,size_t *end) {
    size_t used,p;
    /* The single first question has no earlier name to reference. In
     * particular, bytes inside binary labels/header fields are not names. */
    if(n<12 || be16(w+4)!=1 || !name(w,n,12,&p,q->question,&used,false) || p+4>n || used+4>sizeof(q->question)) return false;
    memcpy(q->question+used,w+p,4); q->question_size=used+4;
    q->wire_id=be16(w); q->flags=be16(w+2); *end=p+4; return true;
}
static bool query(const uint8_t *w,size_t n,Pending *q) {
    size_t p,end; uint8_t owner[255]; size_t owner_size;
    if(n>LINEX_DNS_QUERY_MAX || !parse_question(w,n,q,&p) || (q->flags&0xf800) || be16(w+6) || be16(w+8) || be16(w+10)>1) return false;
    q->udp_limit=512;
    if(be16(w+10)) {
        if(!name(w,n,p,&end,owner,&owner_size,true) || owner_size!=1 || owner[0] || end+10>n || be16(w+end)!=41 || w[end+5]!=0) return false;
        uint16_t limit=be16(w+end+2), bytes=be16(w+end+8);
        q->udp_limit=limit<512?512:limit>1232?1232:limit;
        p=end+10; if(p+bytes!=n) return false;
        size_t finish=p+bytes;
        while(p<finish) { if(p+4>finish) return false; size_t option=be16(w+p+2); p+=4; if(option>finish-p) return false; p+=option; }
    }
    return p==n;
}
static bool same_question(const Pending *a,const Pending *b) {
    if(a->question_size!=b->question_size) return false;
    size_t labels=a->question_size-4;
    for(size_t i=0;i<labels;i++) {
        uint8_t x=a->question[i],y=b->question[i];
        if(x>='A'&&x<='Z') x+=32;
        if(y>='A'&&y<='Z') y+=32;
        if(x!=y) return false;
    }
    return !memcmp(a->question+labels,b->question+labels,4);
}
static bool answer(const uint8_t *w,size_t n,const Pending *q) {
    Pending response={0}; size_t p,end;
    if(n>LINEX_DNS_ANSWER_MAX || !parse_question(w,n,&response,&p) || !(response.flags&0x8000) || (response.flags&0x7800) || response.wire_id!=q->wire_id || !same_question(q,&response)) return false;
    uint32_t records=(uint32_t)be16(w+6)+be16(w+8)+be16(w+10);
    if(records>1024) return false;
    for(uint32_t i=0;i<records;i++) {
        if(!name(w,n,p,&end,NULL,NULL,true) || end+10>n) return false;
        size_t bytes=be16(w+end+8); p=end+10;
        if(bytes>n-p) return false;
        p+=bytes;
    }
    return p==n;
}
static size_t minimal(const Pending *q,uint8_t *w,uint16_t flags) {
    memset(w,0,12); put16(w,q->wire_id); put16(w+2,flags); put16(w+4,1);
    memcpy(w+12,q->question,q->question_size); return 12+q->question_size;
}
static bool frame(LinexDns *d,const Pending *q,uint8_t kind,uint8_t status,const uint8_t *payload,size_t n) {
    uint8_t w[LINEX_DNS_HEADER+LINEX_DNS_QUERY_MAX];
    if(n>LINEX_DNS_QUERY_MAX) return false;
    memset(w,0,LINEX_DNS_HEADER); memcpy(w,"LXDN",4); w[4]=1; w[5]=kind; w[6]=q->transport; w[7]=status;
    put64(w+8,d->generation); put64(w+16,q->id); put32(w+24,(uint32_t)n);
    if(n) memcpy(w+LINEX_DNS_HEADER,payload,n);
    d->in_callback=true;
    bool sent=d->cb.send_frame(w,LINEX_DNS_HEADER+n,d->cb.opaque);
    d->in_callback=false;
    return sent;
}
static bool reply(LinexDns *d,const Pending *q,const uint8_t *w,size_t n) {
    if(q->transport==UDP) {
        d->in_callback=true;
        d->cb.udp_reply(&q->peer,w,n,d->cb.opaque);
        d->in_callback=false;
        return true;
    }
    if(!connection(d,q->connection)) return false;
    size_t charge=sizeof(Response)+LINEX_DNS_HEADER+2+n;
    if(charge>LINEX_DNS_QUEUE_MAX-d->queued) return false;
    Response *r=malloc(sizeof(*r)+2+n); if(!r) return false;
    *r=(Response){.connection=q->connection,.deadline=deadline(d),.size=2+n,.charge=charge};
    put16(r->bytes,(uint16_t)n); memcpy(r->bytes+2,w,n);
    Response **tail=&d->responses; while(*tail) tail=&(*tail)->next; *tail=r; d->queued+=charge; return true;
}
static bool failure(LinexDns *d,const Pending *q,uint8_t status) {
    uint8_t w[272]; size_t n=minimal(q,w,(uint16_t)(0x8080|(q->flags&0x0110)|(status==UNSUPPORTED?5:2)));
    return reply(d,q,w,n);
}
static bool submit(LinexDns *d,Pending *q,const uint8_t *w,size_t n) {
    if(!query(w,n,q)) return false;
    const uint8_t *type=q->question+q->question_size-4;
    if(be16(type+2)!=1 || be16(type)==251 || be16(type)==252) return failure(d,q,UNSUPPORTED);
    Pending *slot=NULL;
    for(size_t i=0;i<LINEX_DNS_PENDING_MAX;i++) if(!d->pending[i].active) { slot=&d->pending[i]; break; }
    if(!slot || d->next_id>INT64_MAX) return failure(d,q,5);
    q->id=d->next_id++; q->deadline=deadline(d); q->active=true; *slot=*q;
    if(!frame(d,q,REQUEST,OK,w,n)) { slot->active=false; return failure(d,q,5); }
    return true;
}
LinexDns *linex_dns_new(uint64_t generation,const LinexDnsCallbacks *cb) {
    if(!generation || generation>INT64_MAX || !cb || !cb->now_ms || !cb->send_frame || !cb->udp_reply || !cb->tcp_reply || !cb->tcp_closed) return NULL;
    LinexDns *d=calloc(1,sizeof(*d)); if(d) { d->generation=generation; d->next_id=1; d->cb=*cb; } return d;
}
bool linex_dns_udp(LinexDns *d,const LinexDnsPeer *peer,const uint8_t *w,size_t n) {
    if(!d || d->in_callback || !peer || !w) return false;
    Pending q={.transport=UDP,.peer=*peer}; return submit(d,&q,w,n);
}
bool linex_dns_tcp_open(LinexDns *d,uint64_t id) {
    if(!d || d->in_callback || !id || id>INT64_MAX || connection(d,id)) return false;
    for(size_t i=0;i<LINEX_DNS_CONNECTION_MAX;i++) if(!d->connections[i].active) {
        d->connections[i]=(Connection){.active=true,.id=id,.deadline=deadline(d)}; return true;
    }
    return false;
}
void linex_dns_tcp_close(LinexDns *d,uint64_t id) {
    if(!d || d->in_callback) return;
    Connection *c=d?connection(d,id):NULL; if(!c) return;
    c->active=false;
    for(size_t i=0;i<LINEX_DNS_PENDING_MAX;i++) if(d->pending[i].active && d->pending[i].connection==id && d->pending[i].transport==TCP) {
        Pending canceled=d->pending[i]; d->pending[i].active=false;
        frame(d,&canceled,CANCEL,STOPPED,NULL,0);
    }
    Response **p=&d->responses;
    while(*p) { Response *r=*p; if(r->connection==id) { *p=r->next; d->queued-=r->charge; free(r); } else p=&r->next; }
}
static void close_tcp(LinexDns *d,uint64_t id) {
    linex_dns_tcp_close(d,id);
    d->in_callback=true;
    d->cb.tcp_closed(id,d->cb.opaque);
    d->in_callback=false;
}
bool linex_dns_tcp_feed(LinexDns *d,uint64_t id,const uint8_t *w,size_t n) {
    if(!d || d->in_callback) return false;
    Connection *c=d?connection(d,id):NULL;
    if(!c || !w || n>16384) return false;
    while(n) {
        if(!c->used) c->deadline=deadline(d);
        size_t target=c->wanted?c->wanted:2;
        size_t take=target-c->used; if(take>n) take=n;
        memcpy(c->input+c->used,w,take); c->used+=take; w+=take; n-=take;
        if(!c->wanted && c->used==2) {
            size_t payload=be16(c->input);
            if(payload<12 || payload>LINEX_DNS_QUERY_MAX) { close_tcp(d,id); return false; }
            c->wanted=2+payload;
        }
        if(c->wanted && c->used==c->wanted) {
            Pending q={.transport=TCP,.connection=id};
            if(!submit(d,&q,c->input+2,c->wanted-2)) { close_tcp(d,id); return false; }
            c->used=c->wanted=0; c->deadline=deadline(d);
        }
    }
    return true;
}
bool linex_dns_receive(LinexDns *d,const uint8_t *w,size_t n) {
    if(!d || d->in_callback || !w || n<LINEX_DNS_HEADER || n>LINEX_DNS_HEADER+LINEX_DNS_ANSWER_MAX || memcmp(w,"LXDN",4) || w[4]!=1 || (w[5]!=ANSWER && w[5]!=ERROR) || (w[6]!=UDP && w[6]!=TCP) || be32(w+28) || be64(w+8)!=d->generation || be32(w+24)!=n-LINEX_DNS_HEADER || !be64(w+16) || be64(w+16)>INT64_MAX) return false;
    if((w[5]==ANSWER && w[7]!=OK) || (w[5]==ERROR && (!w[7] || w[7]>STOPPED || n!=LINEX_DNS_HEADER))) return false;
    Pending *q=NULL;
    for(size_t i=0;i<LINEX_DNS_PENDING_MAX;i++) if(d->pending[i].active && d->pending[i].id==be64(w+16) && d->pending[i].transport==w[6]) { q=&d->pending[i]; break; }
    if(!q || now(d)>=q->deadline) return false;
    if(w[5]==ANSWER && !answer(w+LINEX_DNS_HEADER,n-LINEX_DNS_HEADER,q)) return false;
    Pending selected=*q;
    q->active=false;
    bool delivered;
    if(w[5]==ERROR) delivered=failure(d,&selected,w[7]);
    else {
        const uint8_t *body=w+LINEX_DNS_HEADER; size_t size=n-LINEX_DNS_HEADER;
        uint8_t truncated[272];
        if(selected.transport==UDP && size>selected.udp_limit) { size=minimal(&selected,truncated,(uint16_t)((be16(body+2)|0x0200)&~0x0020)); body=truncated; }
        delivered=reply(d,&selected,body,size);
    }
    if(!delivered && selected.transport==TCP) close_tcp(d,selected.connection);
    return delivered;
}
static bool connection_has_work(const LinexDns *d,uint64_t id) {
    for(size_t i=0;i<LINEX_DNS_PENDING_MAX;i++)
        if(d->pending[i].active && d->pending[i].transport==TCP && d->pending[i].connection==id) return true;
    for(Response *r=d->responses;r;r=r->next) if(r->connection==id) return true;
    return false;
}
void linex_dns_tick(LinexDns *d) {
    if(!d || d->in_callback) return;
    uint64_t t=now(d);
    for(size_t i=0;i<LINEX_DNS_PENDING_MAX;i++) if(d->pending[i].active && t>=d->pending[i].deadline) {
        Pending q=d->pending[i]; d->pending[i].active=false; frame(d,&q,CANCEL,TIMEOUT,NULL,0);
        if(!failure(d,&q,TIMEOUT) && q.transport==TCP) close_tcp(d,q.connection);
    }
    for(size_t i=0;i<LINEX_DNS_CONNECTION_MAX;i++) {
        Connection *c=&d->connections[i];
        if(c->active && t>=c->deadline && (c->used || !connection_has_work(d,c->id))) close_tcp(d,c->id);
    }
    uint64_t blocked[LINEX_DNS_CONNECTION_MAX]; size_t count=0;
    Response **p=&d->responses;
    while(*p) {
        Response *r=*p; bool wait=false;
        for(size_t i=0;i<count;i++) if(blocked[i]==r->connection) wait=true;
        if(t>=r->deadline) { uint64_t id=r->connection; close_tcp(d,id); p=&d->responses; continue; }
        if(wait) { p=&r->next; continue; }
        d->in_callback=true;
        size_t consumed=d->cb.tcp_reply(r->connection,r->bytes+r->offset,r->size-r->offset,d->cb.opaque);
        d->in_callback=false;
        if(consumed>r->size-r->offset) { uint64_t id=r->connection; close_tcp(d,id); p=&d->responses; continue; }
        r->offset+=consumed;
        if(r->offset==r->size) { *p=r->next; d->queued-=r->charge; free(r); }
        else { if(count<LINEX_DNS_CONNECTION_MAX) blocked[count++]=r->connection; p=&r->next; }
    }
}
void linex_dns_free(LinexDns *d) {
    if(!d || d->in_callback) return;
    for(size_t i=0;i<LINEX_DNS_PENDING_MAX;i++) if(d->pending[i].active) {
        Pending canceled=d->pending[i]; d->pending[i].active=false;
        frame(d,&canceled,CANCEL,STOPPED,NULL,0);
    }
    Response *r=d->responses; while(r) { Response *next=r->next; free(r); r=next; } free(d);
}
size_t linex_dns_pending(const LinexDns *d) { size_t n=0; if(d) for(size_t i=0;i<LINEX_DNS_PENDING_MAX;i++) n+=d->pending[i].active; return n; }
size_t linex_dns_connections(const LinexDns *d) { size_t n=0; if(d) for(size_t i=0;i<LINEX_DNS_CONNECTION_MAX;i++) n+=d->connections[i].active; return n; }
size_t linex_dns_queued_bytes(const LinexDns *d) { return d?d->queued:0; }
