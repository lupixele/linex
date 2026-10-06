/* Executes the real native core. Fake callbacks prove this boundary, not Android DNS. */
#include "linex_dns.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    uint64_t clock;
    bool allow_emit, block_tcp;
    size_t frames, udp_count, tcp_size, closed, udp_size, partial;
    uint8_t frame[512][LINEX_DNS_HEADER + LINEX_DNS_QUERY_MAX];
    size_t frame_size[512];
    uint8_t udp[LINEX_DNS_ANSWER_MAX], tcp[LINEX_DNS_ANSWER_MAX + 2];
    LinexDnsPeer peer;
    LinexDns *engine;
    bool reenter_emit, reenter_udp, inside_callback, reentered;
    uint8_t incoming[64];
    size_t incoming_size;
} Fake;
static const uint8_t query[] = {0x12,0x34,1,0,0,1,0,0,0,0,0,0,1,'a',0,0,1,0,1};
static uint64_t clock_ms(void *p) { return ((Fake *)p)->clock; }
static bool emit(const uint8_t *w,size_t n,void *p) {
    Fake *f=p; assert(f->frames<512 && n<=sizeof(f->frame[0]));
    memcpy(f->frame[f->frames],w,n); f->frame_size[f->frames++]=n;
    if(f->reenter_emit && !f->inside_callback) {
        f->inside_callback=true; LinexDnsPeer peer={1,2,3,4};
        f->reentered=linex_dns_udp(f->engine,&peer,query,sizeof(query));
        f->inside_callback=false;
    }
    return f->allow_emit;
}
static void udp_reply(const LinexDnsPeer *peer,const uint8_t *w,size_t n,void *p) {
    Fake *f=p; assert(n<=sizeof(f->udp)); memcpy(f->udp,w,n); f->udp_size=n; f->udp_count++; f->peer=*peer;
    if(f->reenter_udp && !f->inside_callback) {
        f->inside_callback=true;
        f->reentered=linex_dns_receive(f->engine,f->incoming,f->incoming_size);
        f->inside_callback=false;
    }
}
static size_t tcp_reply(uint64_t id,const uint8_t *w,size_t n,void *p) {
    Fake *f=p; assert(id>0); if(f->block_tcp) return 0;
    if(f->partial && n>f->partial) n=f->partial;
    assert(n<=sizeof(f->tcp)-f->tcp_size); memcpy(f->tcp+f->tcp_size,w,n); f->tcp_size+=n; return n;
}
static void closed(uint64_t id,void *p) { assert(id>0); ((Fake *)p)->closed++; }
static LinexDns *create(Fake **f) {
    *f=calloc(1,sizeof(**f)); assert(*f); (*f)->allow_emit=true; (*f)->clock=1000;
    LinexDnsCallbacks cb={clock_ms,emit,udp_reply,tcp_reply,closed,*f};
    LinexDns *d=linex_dns_new(99,&cb); assert(d); (*f)->engine=d; return d;
}
static void release(LinexDns *d,Fake *f) { linex_dns_free(d); free(f); }
static void put32(uint8_t *w,uint32_t n) { w[0]=(uint8_t)(n>>24); w[1]=(uint8_t)(n>>16); w[2]=(uint8_t)(n>>8); w[3]=(uint8_t)n; }
static size_t response(Fake *f,size_t i,uint8_t *w,const uint8_t *body,size_t n,uint8_t kind,uint8_t status) {
    memcpy(w,f->frame[i],LINEX_DNS_HEADER); w[5]=kind; w[7]=status; put32(w+24,(uint32_t)n);
    if(n) memcpy(w+LINEX_DNS_HEADER,body,n); return LINEX_DNS_HEADER+n;
}
static void answer_query(uint8_t *w) { memcpy(w,query,sizeof(query)); w[2]=0x81; w[3]=0x80; }
static void tcp_query(LinexDns *d,uint64_t id) {
    uint8_t packet[sizeof(query)+2]; packet[0]=0; packet[1]=sizeof(query); memcpy(packet+2,query,sizeof(query));
    assert(linex_dns_tcp_feed(d,id,packet,sizeof(packet)));
}
static void udp_and_strict_frames(void) {
    Fake *f; LinexDns *d=create(&f); LinexDnsPeer peer={1,2,3,4}; uint8_t wire[64],body[sizeof(query)];
    assert(linex_dns_udp(d,&peer,query,sizeof(query))); assert(linex_dns_pending(d)==1);
    assert(!memcmp(f->frame[0],"LXDN",4) && f->frame[0][4]==1 && f->frame[0][5]==1);
    assert(f->frame_size[0]==32+sizeof(query)); answer_query(body);
    size_t n=response(f,0,wire,body,sizeof(body),2,0);
    wire[28]=1; assert(!linex_dns_receive(d,wire,n)); wire[28]=0;
    wire[8]=1; assert(!linex_dns_receive(d,wire,n)); wire[8]=0;
    wire[6]=2; assert(!linex_dns_receive(d,wire,n)); wire[6]=1;
    assert(!linex_dns_receive(d,wire,n-1)); wire[32]=0; assert(!linex_dns_receive(d,wire,n)); wire[32]=0x12;
    assert(linex_dns_receive(d,wire,n)); assert(linex_dns_pending(d)==0 && f->udp_count==1);
    assert(f->peer.client_address==1 && f->peer.client_port==3 && !memcmp(f->udp,body,sizeof(body)));
    assert(!linex_dns_receive(d,wire,n)); release(d,f);
}
static void distinct_identity_pending_limit_and_backpressure(void) {
    Fake *f; LinexDns *d=create(&f); LinexDnsPeer peer={1,2,3,4};
    for(size_t i=0;i<64;i++) assert(linex_dns_udp(d,&peer,query,sizeof(query)));
    assert(linex_dns_pending(d)==64 && f->frames==64);
    for(size_t i=1;i<64;i++) assert(memcmp(f->frame[i-1]+16,f->frame[i]+16,8));
    assert(linex_dns_udp(d,&peer,query,sizeof(query))); assert(linex_dns_pending(d)==64 && f->udp_count==1 && (f->udp[3]&15)==2);
    f->clock=11000; linex_dns_tick(d); assert(!linex_dns_pending(d)); assert(f->frames==128);
    for(size_t i=64;i<128;i++) assert(f->frame[i][5]==4 && f->frame[i][7]==4 && f->frame_size[i]==32);
    f->allow_emit=false; assert(linex_dns_udp(d,&peer,query,sizeof(query))); assert(!linex_dns_pending(d));
    release(d,f);
}
static void tcp_partial_pipeline_disconnect_and_timeout(void) {
    Fake *f; LinexDns *d=create(&f); uint8_t packet[sizeof(query)+2],body[sizeof(query)],wire[64];
    assert(linex_dns_tcp_open(d,101)); assert(!linex_dns_tcp_open(d,101));
    packet[0]=0; packet[1]=sizeof(query); memcpy(packet+2,query,sizeof(query));
    for(size_t i=0;i<sizeof(packet);i++) assert(linex_dns_tcp_feed(d,101,packet+i,1));
    tcp_query(d,101); assert(linex_dns_pending(d)==2); answer_query(body);
    size_t n=response(f,0,wire,body,sizeof(body),2,0); assert(linex_dns_receive(d,wire,n));
    f->partial=1; for(size_t i=0;i<sizeof(packet);i++) linex_dns_tick(d);
    assert(f->tcp_size==sizeof(packet) && f->tcp[0]==0 && f->tcp[1]==sizeof(query));
    linex_dns_tcp_close(d,101); assert(!linex_dns_pending(d) && !linex_dns_connections(d));
    assert(f->frame[f->frames-1][5]==4 && f->frame[f->frames-1][7]==7);
    n=response(f,1,wire,body,sizeof(body),2,0); assert(!linex_dns_receive(d,wire,n));
    assert(linex_dns_tcp_open(d,102)); assert(linex_dns_tcp_feed(d,102,packet,1));
    f->clock=11000; linex_dns_tick(d); assert(!linex_dns_connections(d) && f->closed==1);
    release(d,f);
}
static void malformed_lengths_names_and_connection_bound(void) {
    Fake *f; LinexDns *d=create(&f); LinexDnsPeer peer={1,2,3,4}; uint8_t bad[sizeof(query)];
    memcpy(bad,query,sizeof(bad)); bad[12]=0xc0; bad[13]=12;
    assert(!linex_dns_udp(d,&peer,bad,sizeof(bad))); assert(!linex_dns_pending(d));
    const uint8_t forward[]={0x12,0x34,1,0,0,1,0,0,0,0,0,0,0xc0,16,0,1,0,1};
    assert(!linex_dns_udp(d,&peer,forward,sizeof(forward))); assert(!linex_dns_pending(d));
    for(uint64_t i=1;i<=64;i++) assert(linex_dns_tcp_open(d,i));
    assert(!linex_dns_tcp_open(d,65)); assert(linex_dns_connections(d)==64);
    uint8_t oversized[]={4,209}; assert(!linex_dns_tcp_feed(d,1,oversized,2));
    assert(linex_dns_connections(d)==63 && f->closed==1); release(d,f);
}
static void tcp_timeout_delivers_failure_before_idle_cleanup(void) {
    Fake *f; LinexDns *d=create(&f); assert(linex_dns_tcp_open(d,22)); tcp_query(d,22);
    f->clock=11000; linex_dns_tick(d);
    assert(!linex_dns_pending(d)); assert(f->tcp_size==sizeof(query)+2);
    assert((f->tcp[5]&15)==2 && f->frame[f->frames-1][5]==4 && f->frame[f->frames-1][7]==4);
    release(d,f);
}
static void all_callback_reentry_is_refused(void) {
    Fake *f; LinexDns *d=create(&f); LinexDnsPeer peer={1,2,3,4}; uint8_t body[sizeof(query)];
    f->reenter_emit=true; assert(linex_dns_udp(d,&peer,query,sizeof(query)));
    assert(!f->reentered && linex_dns_pending(d)==1); f->reenter_emit=false;
    answer_query(body); f->incoming_size=response(f,0,f->incoming,body,sizeof(body),2,0);
    f->reenter_udp=true; assert(linex_dns_receive(d,f->incoming,f->incoming_size));
    assert(!f->reentered && f->udp_count==1 && !linex_dns_pending(d)); release(d,f);
}
static void udp_truncation_tcp_queue_charge_and_stop(void) {
    Fake *f; LinexDns *d=create(&f); LinexDnsPeer peer={1,2,3,4};
    uint8_t body[8131],wire[LINEX_DNS_HEADER+sizeof(body)]; answer_query(body);
    body[7]=1; size_t p=sizeof(query); body[p++]=0xc0; body[p++]=12;
    body[p++]=0; body[p++]=16; body[p++]=0; body[p++]=1;
    memset(body+p,0,4); p+=4; body[p++]=31; body[p++]=164; memset(body+p,0,8100); p+=8100; assert(p==sizeof(body));
    assert(linex_dns_udp(d,&peer,query,sizeof(query)));
    size_t n=response(f,0,wire,body,sizeof(body),2,0); assert(linex_dns_receive(d,wire,n));
    assert(f->udp_size==sizeof(query) && (f->udp[2]&2) && !f->udp[7]);
    f->block_tcp=true;
    for(uint64_t id=1;id<=64;id++) {
        assert(linex_dns_tcp_open(d,id)); tcp_query(d,id);
        n=response(f,(size_t)id,wire,body,sizeof(body),2,0); (void)linex_dns_receive(d,wire,n);
        assert(linex_dns_queued_bytes(d)<=LINEX_DNS_QUEUE_MAX);
    }
    assert(linex_dns_queued_bytes(d)>0); linex_dns_tick(d); assert(f->tcp_size==0);
    for(uint64_t id=1;id<=64;id++) linex_dns_tcp_close(d,id);
    assert(linex_dns_queued_bytes(d)==0 && !linex_dns_connections(d));
    assert(linex_dns_udp(d,&peer,query,sizeof(query))); size_t before=f->frames;
    linex_dns_free(d); assert(f->frames==before+1 && f->frame[before][5]==4 && f->frame[before][7]==7); free(f);
}
int main(void) {
    udp_and_strict_frames(); distinct_identity_pending_limit_and_backpressure();
    tcp_partial_pipeline_disconnect_and_timeout(); malformed_lengths_names_and_connection_bound();
    tcp_timeout_delivers_failure_before_idle_cleanup(); all_callback_reentry_is_refused();
    udp_truncation_tcp_queue_charge_and_stop(); puts("Native DNS boundary tests passed (Android resolver/SLIRP integration still required)");
    return 0;
}
