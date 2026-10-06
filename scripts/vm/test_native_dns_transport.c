#define _GNU_SOURCE
#include "linex_dns_transport.h"
#include <assert.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/socket.h>
#include <unistd.h>

int main(void)
{
    int pair[2];
    assert(linex_dns_dup_endpoint(-1, 0) == -1);
    assert(linex_dns_dup_endpoint(-1, 1) == -2);
    assert(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
    assert(linex_dns_dup_endpoint(pair[0], 0) == -2);
    assert(linex_dns_dup_endpoint(pair[0], -1) == -2);
    int duplicate = linex_dns_dup_endpoint(pair[0], INT64_MAX);
    assert(duplicate >= 0 && duplicate != pair[0]);
    assert(fcntl(duplicate, F_GETFD) & FD_CLOEXEC);
    assert(close(duplicate) == 0 && fcntl(pair[0], F_GETFD) >= 0);
    const char request[] = "endpoint remains owned by caller";
    char answer[sizeof(request)];
    assert(send(pair[0], request, sizeof(request), 0) == sizeof(request));
    assert(recv(pair[1], answer, sizeof(answer), 0) == sizeof(request));
    assert(fcntl(pair[0], F_SETFD, 0) == 0);
    assert(linex_dns_dup_endpoint(pair[0], 1) == -2);
    assert(fcntl(pair[0], F_SETFD, FD_CLOEXEC) == 0);
    assert(fcntl(pair[0], F_SETFL, 0) == 0);
    assert(linex_dns_dup_endpoint(pair[0], 1) == -2);
    close(pair[0]); close(pair[1]);
    assert(socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0, pair) == 0);
    assert(linex_dns_dup_endpoint(pair[0], 1) == -2);
    close(pair[0]); close(pair[1]);
    puts("Native DNS borrowed endpoint validation and ownership passed");
    return 0;
}
