#define _GNU_SOURCE
/* Reuse the real packet/checksum capture helpers, not a replacement network stack. */
#define main linex_dns_packet_regressions
#include "test_native_slirp_dns.c"
#undef main
#include <poll.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <unistd.h>

typedef struct { struct pollfd fd[128]; size_t count; } PollSet;
static int add_poll(slirp_os_socket fd,int events,void *opaque) {
    PollSet *p=opaque; assert(p->count<128);
    short e=0;
    if(events&SLIRP_POLL_IN) e|=POLLIN;
    if(events&SLIRP_POLL_OUT) e|=POLLOUT;
    if(events&SLIRP_POLL_PRI) e|=POLLPRI;
    p->fd[p->count]=(struct pollfd){.fd=fd,.events=e}; return p->count++;
}
static int poll_result(int index,void *opaque) {
    PollSet *p=opaque; assert(index>=0 && (size_t)index<p->count);
    short e=p->fd[index].revents; int result=0;
    if(e&POLLIN) result|=SLIRP_POLL_IN;
    if(e&POLLOUT) result|=SLIRP_POLL_OUT;
    if(e&POLLPRI) result|=SLIRP_POLL_PRI;
    if(e&POLLERR) result|=SLIRP_POLL_ERR;
    if(e&POLLHUP) result|=SLIRP_POLL_HUP;
    return result;
}
static void pump(Slirp *s) {
    PollSet p={0}; uint32_t timeout=20;
    slirp_pollfds_fill_socket(s,&timeout,add_poll,&p);
    int result=poll(p.fd,p.count,timeout); assert(result>=0);
    slirp_pollfds_poll(s,0,poll_result,&p);
}
static void notify(void *opaque) { (void)opaque; }
static void *timer_new(SlirpTimerCb callback,void *cb_opaque,void *opaque) {
    (void)callback; (void)cb_opaque; (void)opaque;
    /* These packet probes never advance virtual time or require RA discovery. */
    void *timer=malloc(1); assert(timer); return timer;
}
static void timer_free(void *timer,void *opaque) { (void)opaque; free(timer); }
static void timer_mod(void *timer,int64_t expiration,void *opaque) {
    (void)timer; (void)expiration; (void)opaque;
}
static Slirp *create_policy(Fixture *f,bool blocked,bool ipv6) {
    SlirpConfig c={.version=6,.in_enabled=true,.in6_enabled=ipv6,.disable_host_loopback=blocked};
    c.vnetwork.s_addr=htonl(0x0a000200); c.vnetmask.s_addr=htonl(0xffffff00);
    c.vhost.s_addr=htonl(0x0a000202); c.vdhcp_start.s_addr=htonl(0x0a00020f);
    c.vnameserver.s_addr=htonl(0x0a000203); c.vprefix_len=64;
    assert(inet_pton(AF_INET6,"fec0::",&c.vprefix_addr6)==1);
    assert(inet_pton(AF_INET6,"fec0::2",&c.vhost6)==1);
    assert(inet_pton(AF_INET6,"fec0::3",&c.vnameserver6)==1);
    static const SlirpCb cb={.send_packet=output,.clock_get_ns=clock_ns,
        .register_poll_socket=registered,.unregister_poll_socket=unregistered,
        .notify=notify,.timer_new=timer_new,.timer_free=timer_free,.timer_mod=timer_mod};
    Slirp *s=slirp_new(&c,&cb,f); assert(s);
    /* The approved DNS bridge intentionally rejects IPv6 stacks. IPv6 policy
     * probes exercise upstream sockets separately; production remains IPv4. */
    if(!ipv6) assert(slirp_linex_dns_install(s,99,now_ms,emit,f));
    uint8_t mac[]={0x52,0x54,0,0,0,0x15}; struct in6_addr guest;
    assert(inet_pton(AF_INET6,"fec0::15",&guest)==1);
    arp_table_add(s,c.vdhcp_start.s_addr,mac); ndp_table_add(s,guest,mac);
    return s;
}
static size_t network_packet(uint8_t *p,const char *destination,bool ipv6,uint8_t protocol,size_t size) {
    if(!ipv6) {
        size_t n=ipv4(p,protocol,size);
        assert(inet_pton(AF_INET,destination,p+30)==1);
        put16(p+24,0); put16(p+24,checksum(p+14,20,0)); return n;
    }
    memset(p,0,54+size);
    const uint8_t src[]={0x52,0x54,0,0,0,0x15},dst[]={0x52,0x55,10,0,2,2};
    memcpy(p,dst,6); memcpy(p+6,src,6); put16(p+12,0x86dd);
    p[14]=0x60; put16(p+18,size); p[20]=protocol; p[21]=64;
    assert(inet_pton(AF_INET6,"fec0::15",p+22)==1);
    assert(inet_pton(AF_INET6,destination,p+38)==1); return 54+size;
}
static uint32_t pseudo_sum(const uint8_t *p,bool ipv6,uint8_t protocol,size_t size) {
    uint32_t sum=protocol+size;
    for(size_t i=ipv6?22:26;i<(ipv6?54:34);i+=2) sum+=be16(p+i);
    return sum;
}
static void tcp_to(Slirp *s,const char *destination,bool ipv6,uint16_t source_port,uint16_t port,
                   uint32_t seq,uint32_t ack,uint8_t flags,const uint8_t *data,size_t size) {
    uint8_t p[512]; assert(size<400);
    size_t n=network_packet(p,destination,ipv6,6,20+size);
    uint8_t *t=p+(ipv6?54:34);
    put16(t,source_port); put16(t+2,port); put32(t+4,seq); put32(t+8,ack);
    t[12]=0x50; t[13]=flags; put16(t+14,32768);
    if(size) memcpy(t+20,data,size);
    put16(t+16,checksum(t,20+size,pseudo_sum(p,ipv6,6,20+size)));
    slirp_input(s,p,n);
}
static void udp_to(Slirp *s,const char *destination,bool ipv6,uint16_t port) {
    uint8_t p[128]; size_t size=8+sizeof(query);
    size_t n=network_packet(p,destination,ipv6,17,size);
    uint8_t *u=p+(ipv6?54:34);
    put16(u,42000); put16(u+2,port); put16(u+4,size); memcpy(u+8,query,sizeof(query));
    uint16_t sum=checksum(u,size,pseudo_sum(p,ipv6,17,size)); put16(u+6,sum?sum:65535);
    slirp_input(s,p,n);
}
static int loopback_listener(bool ipv6,bool udp_socket,uint16_t *port,const char *destination) {
    int fd=socket(ipv6?AF_INET6:AF_INET,udp_socket?SOCK_DGRAM:SOCK_STREAM,0); assert(fd>=0);
    if(ipv6) {
        struct sockaddr_in6 address={.sin6_family=AF_INET6,.sin6_addr=IN6ADDR_LOOPBACK_INIT};
        assert(bind(fd,(struct sockaddr *)&address,sizeof(address))==0);
        socklen_t size=sizeof(address); assert(getsockname(fd,(struct sockaddr *)&address,&size)==0);
        *port=ntohs(address.sin6_port);
    } else {
        struct sockaddr_in address={.sin_family=AF_INET,.sin_addr={.s_addr=htonl(INADDR_LOOPBACK)}};
        if(strncmp(destination,"127.",4)==0) assert(inet_pton(AF_INET,destination,&address.sin_addr)==1);
        assert(bind(fd,(struct sockaddr *)&address,sizeof(address))==0);
        socklen_t size=sizeof(address); assert(getsockname(fd,(struct sockaddr *)&address,&size)==0);
        *port=ntohs(address.sin_port);
    }
    if(!udp_socket) assert(listen(fd,1)==0);
    return fd;
}
static void check_destination(const char *destination,bool packet_ipv6,bool listener_ipv6,bool blocked) {
    Fixture f={.now=1000}; Slirp *s=create_policy(&f,blocked,packet_ipv6);
    for(int protocol=0;protocol<2;protocol++) {
        uint16_t port; int listener=loopback_listener(listener_ipv6,protocol==1,&port,destination);
        if(protocol==0) tcp_to(s,destination,packet_ipv6,41000,port,1000,0,TH_SYN,NULL,0);
        else udp_to(s,destination,packet_ipv6,port);
        for(int i=0;i<3;i++) pump(s);
        struct pollfd p={.fd=listener,.events=POLLIN};
        assert(poll(&p,1,blocked?50:500)==(blocked?0:1));
        if(!blocked && protocol==0) { int accepted=accept(listener,NULL,NULL); assert(accepted>=0); close(accepted); }
        if(!blocked && protocol==1) {
            uint8_t bytes[128]; assert(recv(listener,bytes,sizeof(bytes),0)==sizeof(query));
            assert(memcmp(bytes,query,sizeof(query))==0);
        }
        close(listener);
    }
    /* Disabling host loopback does not bypass or disable the private DNS broker. */
    if(!packet_ipv6) {
        size_t before=f.frames; udp(s,43000,false); assert(f.frames==before+1);
        answer(s,&f,before); payload_packet(&f,0,17);
    }
    slirp_cleanup(s);
}
static void unix_forward(void) {
    Fixture f={.now=1000}; Slirp *s=create_policy(&f,true,false);
    char directory[]="/tmp/linexunixXXXXXXXX"; assert(mkdtemp(directory));
    assert(chmod(directory,0700)==0);
    struct sockaddr_un host={.sun_family=AF_UNIX};
    assert(snprintf(host.sun_path,sizeof(host.sun_path),"%s/c",directory)>0);
    struct sockaddr_in guest={.sin_family=AF_INET,.sin_addr={.s_addr=htonl(0x0a00020f)},.sin_port=htons(5901)};
    assert(slirp_add_hostxfwd(s,(struct sockaddr *)&host,sizeof(host),(struct sockaddr *)&guest,sizeof(guest),0)==0);
    assert(chmod(host.sun_path,0600)==0);
    struct stat info; assert(lstat(host.sun_path,&info)==0 && S_ISSOCK(info.st_mode) && (info.st_mode&0777)==0600);
    int client=socket(AF_UNIX,SOCK_STREAM,0); assert(client>=0);
    assert(connect(client,(struct sockaddr *)&host,sizeof(host))==0);
    pump(s); assert(f.packets>0);
    size_t syn_index=f.packets-1; const uint8_t *syn=f.packet[syn_index];
    assert(syn[23]==6 && syn[47]==TH_SYN && be16(syn+36)==5901);
    uint16_t peer_port=be16(syn+34); uint32_t server_seq=be32(syn+38)+1;
    char peer_address[INET_ADDRSTRLEN]; assert(inet_ntop(AF_INET,syn+26,peer_address,sizeof(peer_address)));
    tcp_to(s,peer_address,false,5901,peer_port,7000,server_seq,TH_SYN|TH_ACK,NULL,0);
    assert(write(client,"host",4)==4); size_t before=f.packets;
    for(int i=0;i<3;i++) pump(s);
    size_t packet=payload_packet(&f,before,6);
    size_t header=34+4*(f.packet[packet][46]>>4);
    assert(be16(f.packet[packet]+16)==header-14+4 && memcmp(f.packet[packet]+header,"host",4)==0);
    tcp_to(s,peer_address,false,5901,peer_port,7001,server_seq+4,TH_ACK|TH_PUSH,(const uint8_t *)"guest",5);
    for(int i=0;i<3;i++) pump(s);
    struct pollfd ready={.fd=client,.events=POLLIN}; assert(poll(&ready,1,500)==1);
    char bytes[8]; assert(read(client,bytes,sizeof(bytes))==5 && memcmp(bytes,"guest",5)==0);
    tcp_to(s,peer_address,false,5901,peer_port,7006,server_seq+4,TH_ACK|TH_FIN,NULL,0);
    close(client);
    assert(slirp_remove_hostxfwd(s,(struct sockaddr *)&host,sizeof(host),0)==0);
    slirp_cleanup(s);
    assert(unlink(host.sun_path)==0); assert(rmdir(directory)==0);
}
int main(void) {
    const struct { const char *destination; bool packet_ipv6,listener_ipv6; } destinations[]={
        {"10.0.2.2",false,false},{"127.0.0.1",false,false},{"127.0.0.2",false,false},
        {"0.0.0.0",false,false},{"::1",true,true},{"::ffff:127.0.0.1",true,false},
        {"::ffff:0.0.0.0",true,false}
    };
    for(size_t i=0;i<sizeof(destinations)/sizeof(destinations[0]);i++) {
        check_destination(destinations[i].destination,destinations[i].packet_ipv6,destinations[i].listener_ipv6,false);
        check_destination(destinations[i].destination,destinations[i].packet_ipv6,destinations[i].listener_ipv6,true);
    }
    unix_forward();
    puts("Real SLIRP TCP/UDP host-loopback isolation, DNS preservation and private Unix bidirectional forwarding passed");
    return 0;
}
