/* SPDX-License-Identifier: BSD-3-Clause
 * Stack adapter: framing/identity/limits live in linex_dns.c. All entry points
 * are main-loop-only. No native socket is created for the virtual DNS address. */
#include "slirp.h"
#include "linex_slirp_dns.h"

struct LinexSlirpDns {
    LinexDns *core;
    uint64_t next_connection;
    uint64_t (*now_ms)(void *);
    bool (*send_frame)(const uint8_t *, size_t, void *);
    void *opaque;
};

static uint64_t dns_now(void *opaque)
{
    Slirp *slirp=opaque;
    return slirp->linex_dns->now_ms(slirp->linex_dns->opaque);
}
static bool dns_emit(const uint8_t *wire,size_t size,void *opaque)
{
    Slirp *slirp=opaque;
    return slirp->linex_dns->send_frame(wire,size,slirp->linex_dns->opaque);
}
static struct socket *dns_socket(Slirp *slirp,uint64_t id)
{
    for(struct socket *so=slirp->tcb.so_next;so!=&slirp->tcb;so=so->so_next)
        if(so->linex_dns_id==id) return so;
    return NULL;
}
static void dns_udp_reply(const LinexDnsPeer *peer,const uint8_t *wire,size_t size,void *opaque)
{
    Slirp *slirp=opaque;
    struct mbuf *m=m_get(slirp);
    size_t head=IF_MAXLINKHDR+sizeof(struct udpiphdr);
    m_inc(m,(int)(head+size));
    m->m_data+=head;
    memcpy(m->m_data,wire,size); m->m_len=(int)size;
    struct sockaddr_in source={.sin_family=AF_INET,.sin_port=peer->server_port},
                       destination={.sin_family=AF_INET,.sin_port=peer->client_port};
    source.sin_addr.s_addr=peer->server_address;
    destination.sin_addr.s_addr=peer->client_address;
    udp_output(NULL,m,&source,&destination,0);
}
static size_t dns_tcp_reply(uint64_t id,const uint8_t *wire,size_t size,void *opaque)
{
    struct socket *so=dns_socket(opaque,id);
    if(!so || !so->so_tcpcb || so->linex_dns_closing) return size+1;
    struct iovec iov[2]; int count;
    size_t capacity=sopreprbuf(so,iov,&count);
    size_t accepted=MIN(size,capacity);
    if(!accepted) return 0;
    if(soreadbuf(so,(const char *)wire,(int)accepted)<0) return size+1;
    tcp_output(sototcpcb(so));
    return accepted;
}
static void dns_tcp_closed(uint64_t id,void *opaque)
{
    struct socket *so=dns_socket(opaque,id);
    /* Core callbacks cannot reenter it or free a socket beneath tcp_input.
     * Socket teardown runs after input/reply delivery returns to the adapter. */
    if(so) so->linex_dns_closing=true;
}
bool slirp_linex_dns_install(Slirp *slirp,uint64_t generation,
                            uint64_t (*now_ms)(void *),
                            bool (*send_frame)(const uint8_t *,size_t,void *),void *opaque)
{
    if(!slirp || slirp->linex_dns || !now_ms || !send_frame || !slirp->in_enabled || slirp->in6_enabled) return false;
    struct LinexSlirpDns *adapter=g_new0(struct LinexSlirpDns,1);
    adapter->now_ms=now_ms; adapter->send_frame=send_frame;
    adapter->opaque=opaque; adapter->next_connection=1;
    slirp->linex_dns=adapter;
    LinexDnsCallbacks cb={dns_now,dns_emit,dns_udp_reply,dns_tcp_reply,dns_tcp_closed,slirp};
    adapter->core=linex_dns_new(generation,&cb);
    if(!adapter->core) { slirp->linex_dns=NULL; g_free(adapter); return false; }
    return true;
}
bool slirp_linex_dns_udp_input(Slirp *slirp,struct mbuf *m,struct ip *ip,
                               struct udphdr *udp,int iphlen,int len)
{
    if(!slirp->linex_dns || ip->ip_dst.s_addr!=slirp->vnameserver_addr.s_addr || udp->uh_dport!=htons(53)) return false;
    if(len>=(int)sizeof(*udp)) {
        LinexDnsPeer peer={ip->ip_src.s_addr,ip->ip_dst.s_addr,udp->uh_sport,udp->uh_dport};
        linex_dns_udp(slirp->linex_dns->core,&peer,(const uint8_t *)ip+iphlen+sizeof(*udp),len-sizeof(*udp));
    }
    /* Caller frees original mbuf. Even malformed/capacity failures never fall
     * through to the host cleartext DNS resolver. */
    (void)m;
    return true;
}
int slirp_linex_dns_tcp_connect(struct socket *so,unsigned short af)
{
    Slirp *slirp=so->slirp;
    if(!slirp->linex_dns || af!=AF_INET || so->so_faddr.s_addr!=slirp->vnameserver_addr.s_addr || so->so_fport!=htons(53)) return 0;
    struct LinexSlirpDns *adapter=slirp->linex_dns;
    if(adapter->next_connection>INT64_MAX || !linex_dns_tcp_open(adapter->core,adapter->next_connection)) return -1;
    so->linex_dns_id=adapter->next_connection++;
    soisfconnected(so);
    return 1;
}
slirp_ssize_t slirp_linex_dns_tcp_send(struct socket *so,const void *wire,size_t size)
{
    if(!so->linex_dns_closing &&
       !linex_dns_tcp_feed(so->slirp->linex_dns->core,so->linex_dns_id,wire,size)) {
        linex_dns_tcp_close(so->slirp->linex_dns->core,so->linex_dns_id);
        so->linex_dns_closing=true;
    }
    /* Consume invalid input; pending deferred close owns failure. */
    return (slirp_ssize_t)size;
}
void slirp_linex_dns_socket_free(struct socket *so)
{
    if(so->linex_dns_id && so->slirp->linex_dns)
        linex_dns_tcp_close(so->slirp->linex_dns->core,so->linex_dns_id);
    so->linex_dns_id=0;
}
bool slirp_linex_dns_receive(Slirp *slirp,const uint8_t *frame,size_t size)
{
    return slirp && slirp->linex_dns && linex_dns_receive(slirp->linex_dns->core,frame,size);
}
void slirp_linex_dns_tick(Slirp *slirp)
{
    if(!slirp || !slirp->linex_dns) return;
    linex_dns_tick(slirp->linex_dns->core);
    struct socket *so=slirp->tcb.so_next;
    while(so!=&slirp->tcb) {
        struct socket *next=so->so_next;
        if(so->linex_dns_closing) {
            so->linex_dns_id=0;
            /* Finish already accepted bytes using TCP's normal drain/FIN
             * lifecycle; do not reset a connection after SERVFAIL delivery. */
            so->linex_dns_closing=false;
            tcp_sockclosed(sototcpcb(so));
        }
        so=next;
    }
}
void slirp_linex_dns_cleanup(Slirp *slirp)
{
    if(slirp && slirp->linex_dns) {
        linex_dns_free(slirp->linex_dns->core);
        g_free(slirp->linex_dns); slirp->linex_dns=NULL;
    }
}
