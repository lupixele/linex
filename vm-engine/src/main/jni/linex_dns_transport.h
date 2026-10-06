/* Private managed-service endpoint. JNI owns the duplicate, never the caller fd. */
#ifndef LINEX_DNS_TRANSPORT_H
#define LINEX_DNS_TRANSPORT_H
#include <stdint.h>
extern int linex_vm_dns_fd;
extern uint64_t linex_vm_dns_generation;
/* -1 is the exact disabled pair (-1,0); -2 is invalid; >=0 is an owned duplicate. */
int linex_dns_dup_endpoint(int borrowed_fd, int64_t generation);
#endif
