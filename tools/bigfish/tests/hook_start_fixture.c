#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <unistd.h>

#ifdef HOOK_LIBRARY
int fixture_start(void) {
    assert(syscall(SYS_gettid)==getpid());
    if(getenv("HOOK_EXPECT_PENDING")) {
        sigset_t pending;assert(sigpending(&pending)==0);
        assert(sigismember(&pending,SIGALRM)==1);
    }
    int report=open(getenv("HOOK_REPORT"),O_WRONLY|O_CREAT|O_APPEND,0600);
    assert(report>=0 && write(report,"initialized\n",12)==12);close(report);
    uint64_t value=1;int fd=atoi(getenv("HOOK_EVENT_FD"));
    assert(write(fd,&value,sizeof(value))==sizeof(value));
    return 1;
}
#else
static int epoll_fd;
static void *worker(void *unused) {
    (void)unused;struct epoll_event event;
    assert(epoll_wait(epoll_fd,&event,1,250)==0);
    assert(access(getenv("HOOK_REPORT"),F_OK)!=0);
    return NULL;
}
static void alarm_handler(int signal_number) {(void)signal_number;}
int main(int argc,char **argv) {
    assert(argc==2);
    int fd=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK);assert(fd>=0);
    char number[32];snprintf(number,sizeof(number),"%d",fd);
    assert(setenv("HOOK_EVENT_FD",number,1)==0);
    epoll_fd=epoll_create1(EPOLL_CLOEXEC);assert(epoll_fd>=0);
    struct epoll_event event={.events=EPOLLIN,.data.fd=fd};
    assert(epoll_ctl(epoll_fd,EPOLL_CTL_ADD,fd,&event)==0);
    if(!strcmp(argv[1],"finite")) {
        assert(epoll_wait(epoll_fd,&event,1,30)==0);
        assert(access(getenv("HOOK_REPORT"),F_OK)!=0);
    } else if(!strcmp(argv[1],"worker")) {
        pthread_t thread;assert(pthread_create(&thread,NULL,worker,NULL)==0);
        assert(pthread_join(thread,NULL)==0);
    } else if(!strcmp(argv[1],"signal") || !strcmp(argv[1],"masked")) {
        struct sigaction action={.sa_handler=alarm_handler};sigemptyset(&action.sa_mask);
        assert(sigaction(SIGALRM,&action,NULL)==0);
        struct itimerval timer={.it_value={.tv_usec=30000}};
        assert(setitimer(ITIMER_REAL,&timer,NULL)==0);
        if(!strcmp(argv[1],"signal")) {
            assert(epoll_wait(epoll_fd,&event,1,-1)==-1 && errno==EINTR);
            assert(access(getenv("HOOK_REPORT"),F_OK)!=0);
        } else assert(setenv("HOOK_EXPECT_PENDING","1",1)==0);
    }
    int result;
    if(!strcmp(argv[1],"pwait") || !strcmp(argv[1],"masked")) {
        sigset_t mask;sigemptyset(&mask);
        if(!strcmp(argv[1],"masked"))sigaddset(&mask,SIGALRM);
        result=epoll_pwait(epoll_fd,&event,1,-1,&mask);
    } else result=epoll_wait(epoll_fd,&event,1,-1);
    assert(result==1 && event.data.fd==fd && (event.events&EPOLLIN));
    uint64_t value;assert(read(fd,&value,sizeof(value))==sizeof(value) && value==1);
    /* The initializer must not run again on the next event-loop iteration. */
    assert(epoll_wait(epoll_fd,&event,1,0)==0);
    puts("main-thread initialization and real epoll result passed");
    close(epoll_fd);close(fd);return 0;
}
#endif
