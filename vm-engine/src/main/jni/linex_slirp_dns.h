/* Private extension to hash-pinned libslirp4.9.5; never a guest-visible socket. */
#ifndef LINEX_SLIRP_DNS_H
#define LINEX_SLIRP_DNS_H
#include "libslirp.h"
#include "linex_dns.h"
struct mbuf;
struct ip;
struct udphdr;
struct socket;
bool slirp_linex_dns_install(Slirp *slirp, uint64_t generation,
                            uint64_t (*now_ms)(void *),
                            bool (*send_frame)(const uint8_t *, size_t, void *),
                            void *opaque);
bool slirp_linex_dns_receive(Slirp *slirp, const uint8_t *frame, size_t size);
void slirp_linex_dns_tick(Slirp *slirp);
void slirp_linex_dns_cleanup(Slirp *slirp);
/* Internal transport entry points: the patched stack retains IP/TCP ownership. */
bool slirp_linex_dns_udp_input(Slirp *slirp, struct mbuf *m,
                               struct ip *ip, struct udphdr *udp, int iphlen, int len);
int slirp_linex_dns_tcp_connect(struct socket *so, unsigned short af);
slirp_ssize_t slirp_linex_dns_tcp_send(struct socket *so, const void *wire, size_t size);
void slirp_linex_dns_socket_free(struct socket *so);
#endif
