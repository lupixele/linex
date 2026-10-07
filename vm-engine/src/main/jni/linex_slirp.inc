/* SPDX-License-Identifier: GPL-2.0-or-later
 * Included by QEMU's net/slirp.c after SlirpState. The bridge is confined to
 * the main AioContext. vCPU receive callbacks only copy into a bounded FIFO.
 * All queue, callback and teardown paths hold the BQL. */
struct LinexVmPacket {
    struct LinexVmPacket *next;
    size_t size;
    uint8_t bytes[];
};

#define LINEX_PACKET_QUEUE_MAX (512u * 1024u)
#define LINEX_PACKET_COUNT_MAX 512u
#define LINEX_PACKET_MAX 65536u

static uint64_t linex_slirp_now_ms(void *opaque)
{
    (void)opaque;
    return qemu_clock_get_ms(QEMU_CLOCK_REALTIME);
}

static bool linex_slirp_send_frame(const uint8_t *wire, size_t size, void *opaque)
{
    SlirpState *s = opaque;
    assert(bql_locked() && qemu_in_main_thread());
    if (s->linex_dns_dead || s->linex_stopping ||
        size > LINEX_DNS_HEADER + LINEX_DNS_QUERY_MAX) {
        return false;
    }
    /* Atomic datagrams: no partial write queue or blocking resolver on QEMU's
     * thread. EAGAIN rejects admission, which the core returns as REFUSED. */
    ssize_t sent = send(linex_vm_dns_fd, wire, size, MSG_DONTWAIT | MSG_NOSIGNAL);
    return sent == size;
}

static void linex_slirp_read(void *opaque)
{
    SlirpState *s = opaque;
    assert(bql_locked() && qemu_in_main_thread());
    for (unsigned i = 0; i < 64 && !s->linex_stopping; i++) {
        uint8_t wire[LINEX_DNS_HEADER + LINEX_DNS_ANSWER_MAX];
        struct iovec iov = { .iov_base = wire, .iov_len = sizeof(wire) };
        struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1 };
        ssize_t size = recvmsg(linex_vm_dns_fd, &msg, MSG_DONTWAIT | MSG_TRUNC);
        if (size < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
            break;
        }
        if (size <= 0) {
            s->linex_dns_dead = true;
            aio_set_fd_handler(qemu_get_aio_context(), linex_vm_dns_fd,
                               NULL, NULL, NULL, NULL, NULL);
            break;
        }
        if (!(msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) && size <= sizeof(wire)) {
            slirp_linex_dns_receive(s->slirp, wire, size);
        }
    }
    slirp_linex_dns_tick(s->slirp);
}

static void linex_slirp_timer(void *opaque)
{
    SlirpState *s = opaque;
    assert(bql_locked() && qemu_in_main_thread());
    if (!s->linex_stopping) {
        slirp_linex_dns_tick(s->slirp);
        /* Real time remains active with the guest paused. */
        timer_mod(s->linex_dns_timer,
                  qemu_clock_get_ms(QEMU_CLOCK_REALTIME) + 100);
    }
}

static void linex_slirp_drain(void *opaque)
{
    SlirpState *s = opaque;
    assert(bql_locked() && qemu_in_main_thread());
    for (unsigned i = 0; i < 64 && s->linex_head && !s->linex_stopping; i++) {
        struct LinexVmPacket *packet = s->linex_head;
        s->linex_head = packet->next;
        if (!s->linex_head) {
            s->linex_tail = NULL;
        }
        s->linex_bytes -= sizeof(*packet) + packet->size;
        s->linex_packets--;
        slirp_input(s->slirp, packet->bytes, packet->size);
        free(packet);
        slirp_linex_dns_tick(s->slirp);
    }
    if (s->linex_head && !s->linex_stopping) {
        qemu_bh_schedule(s->linex_input_bh);
    }
}

static void linex_slirp_enqueue(SlirpState *s, const uint8_t *buf, size_t size)
{
    assert(bql_locked());
    if (s->linex_stopping || size > LINEX_PACKET_MAX ||
        s->linex_packets >= LINEX_PACKET_COUNT_MAX ||
        size + sizeof(struct LinexVmPacket) > LINEX_PACKET_QUEUE_MAX - s->linex_bytes) {
        return;
    }
    struct LinexVmPacket *packet = malloc(sizeof(*packet) + size);
    if (!packet) {
        return;
    }
    packet->next = NULL;
    packet->size = size;
    memcpy(packet->bytes, buf, size);
    if (s->linex_tail) {
        s->linex_tail->next = packet;
    } else {
        s->linex_head = packet;
    }
    s->linex_tail = packet;
    s->linex_packets++;
    s->linex_bytes += sizeof(*packet) + size;
    qemu_bh_schedule(s->linex_input_bh);
}

static bool linex_slirp_start(SlirpState *s, Error **errp)
{
    assert(bql_locked() && qemu_in_main_thread());
    if (linex_vm_dns_fd < 0 || !linex_vm_dns_generation ||
        !slirp_linex_dns_install(s->slirp, linex_vm_dns_generation,
                                linex_slirp_now_ms, linex_slirp_send_frame, s)) {
        error_setg(errp, "SLIRP requires the managed Android DNS bridge");
        return false;
    }
    s->linex_input_bh = aio_bh_new(qemu_get_aio_context(), linex_slirp_drain, s);
    s->linex_dns_timer = timer_new_ms(QEMU_CLOCK_REALTIME, linex_slirp_timer, s);
    s->linex_active = true;
    aio_set_fd_handler(qemu_get_aio_context(), linex_vm_dns_fd,
                       linex_slirp_read, NULL, NULL, NULL, s);
    timer_mod(s->linex_dns_timer, qemu_clock_get_ms(QEMU_CLOCK_REALTIME) + 100);
    return true;
}

static void linex_slirp_stop(SlirpState *s)
{
    assert(bql_locked());
    s->linex_stopping = true;
    if (s->linex_active) {
        aio_set_fd_handler(qemu_get_aio_context(), linex_vm_dns_fd,
                           NULL, NULL, NULL, NULL, NULL);
        timer_del(s->linex_dns_timer);
        timer_free(s->linex_dns_timer);
        qemu_bh_cancel(s->linex_input_bh);
        qemu_bh_delete(s->linex_input_bh);
        s->linex_active = false;
    }
    while (s->linex_head) {
        struct LinexVmPacket *next = s->linex_head->next;
        free(s->linex_head);
        s->linex_head = next;
    }
    s->linex_tail = NULL;
    s->linex_bytes = s->linex_packets = 0;
    /* JNI closes its duplicate only after qemu_cleanup returns. */
}
