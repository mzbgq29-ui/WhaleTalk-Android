#define _GNU_SOURCE
#include <sys/uio.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <assert.h>
#include <stdint.h>
#include <limits.h>
static void deny_vm(void){
 struct sock_filter ins[]={BPF_STMT(BPF_LD|BPF_W|BPF_ABS,offsetof(struct seccomp_data,nr)),BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K,SYS_process_vm_readv,0,1),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ERRNO|EPERM),BPF_STMT(BPF_RET|BPF_K,SECCOMP_RET_ALLOW)};
 struct sock_fprog p={sizeof(ins)/sizeof(ins[0]),ins};assert(prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)==0);assert(prctl(PR_SET_SECCOMP,SECCOMP_MODE_FILTER,&p)==0);
}
int main(int argc,char **argv){
 (void)argv;deny_vm();char source[]="new-qq-self-memory",out[32]={0};
 struct iovec l[]={{out,4},{out+4,14}},r[]={{source,2},{source+2,0},{source+2,16}};
 ssize_t n=process_vm_readv(getpid(),l,2,r,3,0);
 if(argc==1){assert(n==-1&&errno==EPERM);puts("disabled_preserves_kernel_denial");return 0;}
 assert(n==18&&!memcmp(source,out,18));puts("denied_syscall_self_read_and_split_vectors");
 n=process_vm_readv(getpid()+1,l,2,r,3,0);assert(n==-1&&errno==EPERM);puts("remote_process_not_fallback");
 n=process_vm_readv(getpid(),l,2,r,3,1);assert(n==-1&&errno==EPERM);puts("nonzero_flags_not_fallback");
 n=process_vm_readv(getpid(),NULL,0,NULL,0,0);assert(n==0);puts("empty_vectors");
 struct iovec bad={ (void *)1,4};n=process_vm_readv(getpid(),l,2,&bad,1,0);assert(n==-1&&(errno==EIO||errno==EFAULT));puts("unmapped_address_error_without_crash");
 n=process_vm_readv(getpid(),NULL,1,r,3,0);assert(n==-1&&errno==EFAULT);puts("null_vector_error");
 struct iovec big[]={{out,(size_t)SSIZE_MAX},{out,1}};n=process_vm_readv(getpid(),big,2,r,3,0);assert(n==-1&&errno==EINVAL);puts("overflow_error");
 return 0;
}
