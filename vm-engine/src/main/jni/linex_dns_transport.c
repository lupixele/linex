#define _GNU_SOURCE
#include "linex_dns_transport.h"
#include <fcntl.h>
#include <stddef.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int linex_vm_dns_fd = -1;
uint64_t linex_vm_dns_generation;

int linex_dns_dup_endpoint(int borrowed_fd, int64_t generation)
{
    if (borrowed_fd == -1 && generation == 0) return -1;
    if (borrowed_fd < 0 || generation <= 0) return -2;
    int flags = fcntl(borrowed_fd, F_GETFL);
    int fdflags = fcntl(borrowed_fd, F_GETFD);
    int type = 0;
    socklen_t size = sizeof(type);
    struct sockaddr_un local = {0}, peer = {0};
    socklen_t local_size = sizeof(local), peer_size = sizeof(peer);
    if (flags < 0 || !(flags & O_NONBLOCK) || fdflags < 0 || !(fdflags & FD_CLOEXEC) ||
        getsockopt(borrowed_fd, SOL_SOCKET, SO_TYPE, &type, &size) ||
        size != sizeof(type) || type != SOCK_SEQPACKET ||
        getsockname(borrowed_fd, (struct sockaddr *)&local, &local_size) ||
        getpeername(borrowed_fd, (struct sockaddr *)&peer, &peer_size) ||
        local.sun_family != AF_UNIX || peer.sun_family != AF_UNIX ||
        local_size != offsetof(struct sockaddr_un, sun_path) ||
        peer_size != offsetof(struct sockaddr_un, sun_path)) return -2;
    int duplicate = fcntl(borrowed_fd, F_DUPFD_CLOEXEC, 0);
    return duplicate < 0 ? -2 : duplicate;
}
