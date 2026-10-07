/* Real hash-pinned libslirp packet/TCP lifecycle regression, not a socket mock.
 * A deterministic Android reply callback tests the native boundary only. */
#include "slirp.h"
#include "linex_slirp_dns.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    uint64_t now;
    size_t frames, packets, polls;
    uint8_t frame[128][LINEX_DNS_HEADER + LINEX_DNS_QUERY_MAX];
    size_t frame_size[128];
    uint8_t packet[256][2048];
    size_t packet_size[256];
} Fixture;
static const uint8_t query[] = {0x12,0x34,1,0,0,1,0,0,0,0,0,0,1,'a',0,0,1,0,1};
static uint16_t be16(const uint8_t *p) { return (p[0] << 8) | p[1]; }
static uint32_t be32(const uint8_t *p) { return ((uint32_t)be16(p) << 16) | be16(p+2); }
static void put16(uint8_t *p,uint16_t n) { p[0]=n>>8; p[1]=n; }
static void put32(uint8_t *p,uint32_t n) { put16(p,n>>16); put16(p+2,n); }
static uint16_t checksum(const uint8_t *p,size_t n,uint32_t sum) {
    while(n>1) { sum+=be16(p); p+=2; n-=2; }
    if(n) sum+=(uint16_t)*p<<8;
    while(sum>>16) sum=(sum&65535)+(sum>>16);
    return ~sum;
}
static uint64_t now_ms(void *p) { return ((Fixture *)p)->now; }
static int64_t clock_ns(void *p) { return now_ms(p)*1000000; }
static bool emit(const uint8_t *p,size_t n,void *opaque) {
    Fixture *f=opaque; assert(f->frames<128 && n<=sizeof(f->frame[0]));
    memcpy(f->frame[f->frames],p,n); f->frame_size[f->frames++]=n; return true;
}
static slirp_ssize_t output(const void *p,size_t n,void *opaque) {
    Fixture *f=opaque; assert(f->packets<256 && n<=sizeof(f->packet[0]));
    memcpy(f->packet[f->packets],p,n); f->packet_size[f->packets++]=n; return n;
}
static void registered(slirp_os_socket fd,void *opaque) {
    (void)fd; ((Fixture *)opaque)->polls++;
}
static void unregistered(slirp_os_socket fd,void *opaque) { (void)fd; (void)opaque; }
static Slirp *create(Fixture *f) {
    SlirpConfig c={.version=6,.in_enabled=true};
    c.vnetwork.s_addr=htonl(0x0a000200); c.vnetmask.s_addr=htonl(0xffffff00);
    c.vhost.s_addr=htonl(0x0a000202); c.vdhcp_start.s_addr=htonl(0x0a00020f);
    c.vnameserver.s_addr=htonl(0x0a000203);
    static const SlirpCb cb={.send_packet=output,.clock_get_ns=clock_ns,
                            .register_poll_socket=registered,.unregister_poll_socket=unregistered};
    Slirp *s=slirp_new(&c,&cb,f); assert(s);
    assert(slirp_linex_dns_install(s,99,now_ms,emit,f));
    const uint8_t mac[]={0x52,0x54,0,0,0,0x15};
    arp_table_add(s,c.vdhcp_start.s_addr,mac);
    return s;
}
static size_t ipv4(uint8_t *p,uint8_t protocol,size_t transport) {
    memset(p,0,14+20+transport);
    const uint8_t dst[]={0x52,0x55,10,0,2,3},src[]={0x52,0x54,0,0,0,0x15};
    memcpy(p,dst,6); memcpy(p+6,src,6); put16(p+12,0x0800);
    uint8_t *ip=p+14; ip[0]=0x45; put16(ip+2,20+transport); ip[8]=64; ip[9]=protocol;
    put32(ip+12,0x0a00020f); put32(ip+16,0x0a000203);
    put16(ip+10,checksum(ip,20,0)); return 14+20+transport;
}
static void udp(Slirp *s,uint16_t port,bool bad_checksum) {
    uint8_t p[128]; size_t n=ipv4(p,17,8+sizeof(query));
    put16(p+34,port); put16(p+36,53); put16(p+38,8+sizeof(query));
    memcpy(p+42,query,sizeof(query));
    if(bad_checksum) put16(p+40,1);
    slirp_input(s,p,n);
}
static void tcp(Slirp *s,uint16_t port,uint32_t seq,uint32_t ack,uint8_t flags,const uint8_t *data,size_t size) {
    uint8_t p[2048]; assert(size<sizeof(p)-54);
    size_t n=ipv4(p,6,20+size);
    uint8_t *t=p+34;
    put16(t,port); put16(t+2,53); put32(t+4,seq); put32(t+8,ack);
    t[12]=0x50; t[13]=flags; put16(t+14,32768);
    if(size) memcpy(t+20,data,size);
    uint32_t pseudo=be16(p+26)+be16(p+28)+be16(p+30)+be16(p+32)+6+20+size;
    put16(t+16,checksum(t,20+size,pseudo));
    slirp_input(s,p,n);
}
static void answer(Slirp *s,Fixture *f,size_t index) {
    uint8_t frame[128]; size_t n=f->frame_size[index]; assert(n<sizeof(frame));
    memcpy(frame,f->frame[index],n); frame[5]=2;
    frame[LINEX_DNS_HEADER+2]=0x81; frame[LINEX_DNS_HEADER+3]=0x80;
    assert(slirp_linex_dns_receive(s,frame,n)); slirp_linex_dns_tick(s);
}
static size_t payload_packet(Fixture *f,size_t after,uint8_t protocol) {
    for(size_t i=after;i<f->packets;i++) {
        const uint8_t *p=f->packet[i];
        if(f->packet_size[i]<54 || p[23]!=protocol) continue;
        size_t head=protocol==6?34+4*(p[46]>>4):42;
        if(f->packet_size[i]>head && be16(p+16)>head-14) return i;
    }
    assert(!"Expected DNS payload packet"); return 0;
}
static size_t sockets(Slirp *s) {
    size_t n=0; for(struct socket *p=s->tcb.so_next;p!=&s->tcb;p=p->so_next) { assert(++n<256); }
    return n;
}
int main(void) {
    /* Queue pointers must remain the leading socket fields. */
    _Static_assert(offsetof(struct socket,so_next)==0,"SLIRP intrusive queue layout");
    _Static_assert((sizeof(struct tcpiphdr)-sizeof(struct ip)-sizeof(struct tcphdr))%
                   _Alignof(struct qlink)==0,"TCP overlay must preserve queue pointer alignment");
    Fixture f={.now=1000}; Slirp *s=create(&f);
    udp(s,40000,true); assert(f.frames==0 && f.polls==0);
    udp(s,40000,false); assert(f.frames==1 && f.frame[0][6]==1 && f.polls==0);
    answer(s,&f,0);
    size_t i=payload_packet(&f,0,17);
    assert(be16(f.packet[i]+36)==40000 && f.packet[i][44]==0x81);
    assert(s->udb.so_next==&s->udb); /* No host UDP resolver socket. */

    size_t before=f.packets;
    tcp(s,40001,12345,0,TH_SYN,NULL,0);
    assert(sockets(s)==1 && f.polls==0 && f.packets>before);
    const uint8_t *syn=f.packet[f.packets-1];
    assert(syn[47]==(TH_SYN|TH_ACK)); uint32_t server=be32(syn+38)+1;
    tcp(s,40001,12346,server,TH_ACK,NULL,0);
    uint8_t framed[sizeof(query)+2]; put16(framed,sizeof(query)); memcpy(framed+2,query,sizeof(query));
    tcp(s,40001,12346,server,TH_ACK,framed,1); assert(f.frames==1);
    tcp(s,40001,12347,server,TH_ACK,framed+1,sizeof(framed)-1);
    assert(f.frames==2 && f.frame[1][6]==2 && f.polls==0);
    before=f.packets; answer(s,&f,1); i=payload_packet(&f,before,6);
    size_t head=34+4*(f.packet[i][46]>>4);
    assert(be16(f.packet[i]+head)==sizeof(query));
    assert(f.packet[i][head+4]==0x81 && sockets(s)==1);
    /* ACK output then issue another request and deliver timeout SERVFAIL. */
    server=be32(f.packet[i]+38)+(be16(f.packet[i]+16)-(head-14));
    tcp(s,40001,12346+sizeof(framed),server,TH_ACK,framed,sizeof(framed));
    assert(f.frames==3);
    before=f.packets; f.now+=10001; slirp_linex_dns_tick(s);
    i=payload_packet(&f,before,6); head=34+4*(f.packet[i][46]>>4);
    assert((f.packet[i][head+5]&15)==2 && sockets(s)==1);

    /* Exhaust the native context cap using real SYNs. Capacity rejection must
     * free its embryonic tcpcb before responding with RST. */
    for(unsigned j=0;j<63;j++) tcp(s,41000+j,5000+j,0,TH_SYN,NULL,0);
    assert(sockets(s)==64);
    tcp(s,42000,9000,0,TH_SYN,NULL,0); assert(sockets(s)==64);
    assert((f.packet[f.packets-1][47]&TH_RST)!=0 && f.polls==0);
    /* Core IDs retire at the real-time deadline, but a peer can withhold FIN.
     * The independent socket budget must remain charged until actual sofree. */
    f.now+=10001; slirp_linex_dns_tick(s);
    assert(sockets(s)==64);
    for(struct socket *so=s->tcb.so_next;so!=&s->tcb;so=so->so_next)
        assert(!so->linex_dns_id && so->linex_dns_owned);
    for(unsigned j=0;j<64;j++) tcp(s,43000+j,10000+j,0,TH_SYN,NULL,0);
    assert(sockets(s)==64 && f.polls==0);
    slirp_cleanup(s);
    puts("Real SLIRP UDP/TCP bridge, checksum, partial framing, timeout and capacity passed");
    return 0;
}
