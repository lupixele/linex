/* Private DNS bridge core. All calls and callbacks run on the QEMU event loop. */
#ifndef LINEX_DNS_H
#define LINEX_DNS_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define LINEX_DNS_HEADER 32u
#define LINEX_DNS_QUERY_MAX 1232u
#define LINEX_DNS_ANSWER_MAX 8192u
#define LINEX_DNS_PENDING_MAX 64u
#define LINEX_DNS_CONNECTION_MAX 64u
#define LINEX_DNS_QUEUE_MAX (512u * 1024u)
#define LINEX_DNS_DEADLINE_MS 10000u

typedef struct LinexDns LinexDns;
/* IPv4 addresses and ports retain network byte order. No native pointer crosses IPC. */
typedef struct {
    uint32_t client_address, server_address;
    uint16_t client_port, server_port;
} LinexDnsPeer;
typedef struct {
    /* Every callback is nonreentrant: mutating core APIs called from a callback
     * are refused. Schedule lifecycle/input work after the callback returns. */
    uint64_t (*now_ms)(void *opaque);
    /* Borrowed frame: copy/send synchronously without blocking; false rejects admission. */
    bool (*send_frame)(const uint8_t *frame, size_t size, void *opaque);
    void (*udp_reply)(const LinexDnsPeer *peer, const uint8_t *wire, size_t size, void *opaque);
    /* Return consumed bytes, zero for backpressure. Must not reenter this core. */
    size_t (*tcp_reply)(uint64_t connection, const uint8_t *wire, size_t size, void *opaque);
    void (*tcp_closed)(uint64_t connection, void *opaque);
    void *opaque;
} LinexDnsCallbacks;

LinexDns *linex_dns_new(uint64_t generation, const LinexDnsCallbacks *callbacks);
void linex_dns_free(LinexDns *dns);
bool linex_dns_udp(LinexDns *dns, const LinexDnsPeer *peer, const uint8_t *wire, size_t size);
bool linex_dns_tcp_open(LinexDns *dns, uint64_t connection);
bool linex_dns_tcp_feed(LinexDns *dns, uint64_t connection, const uint8_t *bytes, size_t size);
void linex_dns_tcp_close(LinexDns *dns, uint64_t connection);
bool linex_dns_receive(LinexDns *dns, const uint8_t *frame, size_t size);
void linex_dns_tick(LinexDns *dns);
size_t linex_dns_pending(const LinexDns *dns);
size_t linex_dns_connections(const LinexDns *dns);
size_t linex_dns_queued_bytes(const LinexDns *dns);
#endif
