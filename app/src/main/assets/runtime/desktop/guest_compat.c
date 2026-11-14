/* Independent glibc guest compatibility helpers. No application payloads. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern char **environ;
static bool enabled(const char *key) { const char *v=getenv(key); return v && !strcmp(v,"1"); }
static int raw_open(const char *p,int flags,mode_t mode) { return syscall(SYS_openat,AT_FDCWD,p,flags,mode); }
static bool copy_fd(int from,int to,off_t size) {
    char data[16384]; off_t at=0;
    while(at<size) {
        ssize_t got=pread(from,data,(size-at)<(off_t)sizeof(data)?(size_t)(size-at):sizeof(data),at);
        if(got<0 && errno==EINTR)continue;
        if(got<=0)return false;
        ssize_t out=0;
        while(out<got) { ssize_t n=write(to,data+out,got-out);if(n<0 && errno==EINTR)continue;if(n<=0)return false;out+=n; }
        at+=got;
    }
    return true;
}
static int local_memfd(const char *path) {
    if(!path)return -1;
    const char *number=NULL;
    const char *prefixes[]={"/proc/self/fd/","/proc/thread-self/fd/","/dev/fd/"};
    for(size_t n=0;n<3;n++)if(!strncmp(path,prefixes[n],strlen(prefixes[n])))number=path+strlen(prefixes[n]);
    char pid_prefix[64];snprintf(pid_prefix,sizeof(pid_prefix),"/proc/%d/fd/",getpid());
    if(!strncmp(path,pid_prefix,strlen(pid_prefix)))number=path+strlen(pid_prefix);
    if(!number || !*number)return -1;
    for(const char *p=number;*p;p++)if(*p<'0'||*p>'9')return -1;
    errno=0;long fd=strtol(number,NULL,10);if(errno || fd>INT_MAX)return -1;
    char link[PATH_MAX];ssize_t length=readlink(path,link,sizeof(link)-1);
    if(length<0)return -1;
    link[length]=0;
    if(strncmp(link,"/memfd:",7) && strncmp(link,"memfd:",6))return -1;
    return (int)fd;
}
void *dlopen(const char *path,int flags) {
    void *(*next)(const char *,int)=dlsym(RTLD_NEXT,"dlopen");
    int fd=enabled("ASTRBOT_COMPAT_MEMFD")?local_memfd(path):-1;
    if(fd<0)return next(path,flags);
    struct stat st;if(fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_size<=0 || st.st_size>256*1024*1024)return next(path,flags);
    unsigned char magic[4];if(pread(fd,magic,4,0)!=4 || memcmp(magic,"\177ELF",4))return next(path,flags);
    const char *base=getenv("ASTRBOT_COMPAT_TMPDIR");if(!base || !*base)base="/tmp";
    char directory[PATH_MAX],file[PATH_MAX];
    if(snprintf(directory,sizeof(directory),"%s/guest-dlopen-XXXXXX",base)>=(int)sizeof(directory) || !mkdtemp(directory))return next(path,flags);
    if(snprintf(file,sizeof(file),"%s/image.so",directory)>=(int)sizeof(file)){rmdir(directory);return next(path,flags);}
    int out=raw_open(file,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC,0600);
    bool copied=out>=0 && copy_fd(fd,out,st.st_size);
    if(out>=0)close(out);
    void *handle=copied?next(file,flags):next(path,flags);
    unlink(file);rmdir(directory);
    return handle;
}

/* Ask a separate process about real kernel locks; never close the caller's
 * descriptors or infer locks merely from a file being open. */
static int lock_snapshot(int flags) {
    if(!enabled("ASTRBOT_COMPAT_PROC_LOCKS") || (flags&O_ACCMODE)!=O_RDONLY)return -1;
    const char *helper=getenv("ASTRBOT_COMPAT_LOCK_HELPER");if(!helper || !*helper)return -1;
    int fd=syscall(SYS_memfd_create,"guest-proc-locks",1U);
    if(fd<0)return -1;
    posix_spawn_file_actions_t actions;
    posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_adddup2(&actions,fd,STDOUT_FILENO);
    char *argv[]={"python3",(char *)helper,NULL};pid_t child;
    int result=posix_spawn(&child,"/usr/bin/python3",&actions,NULL,argv,environ);
    posix_spawn_file_actions_destroy(&actions);
    int status=0;
    if(!result){while(waitpid(child,&status,0)<0){if(errno!=EINTR){result=-1;break;}}}
    if(result || !WIFEXITED(status) || WEXITSTATUS(status)!=0 || lseek(fd,0,SEEK_SET)<0){close(fd);return -1;}
    if(!(flags&O_CLOEXEC))fcntl(fd,F_SETFD,0);
    return fd;
}
static int open_path(int dirfd,const char *path,int flags,mode_t mode) {
    int fd=syscall(SYS_openat,dirfd,path,flags,mode);
    if(fd<0 && (errno==EACCES || errno==EPERM) && path && !strcmp(path,"/proc/locks")) {
        int original=errno;fd=lock_snapshot(flags);if(fd<0)errno=original;
    }
    return fd;
}
#define OPEN_WRAPPER(name) int name(const char *path,int flags,...) { mode_t mode=0;if((flags&O_CREAT)||((flags&O_TMPFILE)==O_TMPFILE)){va_list args;va_start(args,flags);mode=va_arg(args,mode_t);va_end(args);}return open_path(AT_FDCWD,path,flags,mode); }
#define OPENAT_WRAPPER(name) int name(int dirfd,const char *path,int flags,...) { mode_t mode=0;if((flags&O_CREAT)||((flags&O_TMPFILE)==O_TMPFILE)){va_list args;va_start(args,flags);mode=va_arg(args,mode_t);va_end(args);}return open_path(dirfd,path,flags,mode); }
OPEN_WRAPPER(open)
OPEN_WRAPPER(open64)
OPENAT_WRAPPER(openat)
OPENAT_WRAPPER(openat64)

/* Some Android kernels reject process_vm_readv even for the calling process.
 * The injected backend needs only its own address space. Keep remote reads and
 * normal kernel results unchanged; /proc/self/mem remains subject to OS access
 * checks and returns an error for unmapped pages instead of dereferencing them.
 */
ssize_t process_vm_readv(pid_t pid,const struct iovec *local,unsigned long local_count,
                        const struct iovec *remote,unsigned long remote_count,unsigned long flags) {
    ssize_t result=syscall(SYS_process_vm_readv,pid,local,local_count,remote,remote_count,flags);
    int original=errno;
    if(result>=0 || !enabled("ASTRBOT_COMPAT_SELF_MEMORY") || pid!=getpid() || flags ||
       (original!=EPERM && original!=EACCES && original!=ENOSYS))return result;
    if(local_count>1024 || remote_count>1024){errno=EINVAL;return -1;}
    if(!local_count || !remote_count)return 0;
    if(!local || !remote){errno=EFAULT;return -1;}
    size_t local_size=0,remote_size=0;
    for(unsigned long i=0;i<local_count;i++) {
        if(local[i].iov_len>(size_t)SSIZE_MAX-local_size){errno=EINVAL;return -1;}
        local_size+=local[i].iov_len;
    }
    for(unsigned long i=0;i<remote_count;i++) {
        if(remote[i].iov_len>(size_t)SSIZE_MAX-remote_size){errno=EINVAL;return -1;}
        remote_size+=remote[i].iov_len;
    }
    if(!local_size || !remote_size)return 0;
    int fd=raw_open("/proc/self/mem",O_RDONLY|O_CLOEXEC,0);
    if(fd<0)return -1;
    unsigned long li=0,ri=0;size_t lo=0,ro=0;ssize_t total=0;
    while(li<local_count && ri<remote_count) {
        if(lo==local[li].iov_len){li++;lo=0;continue;}
        if(ro==remote[ri].iov_len){ri++;ro=0;continue;}
        size_t length=local[li].iov_len-lo;
        if(length>remote[ri].iov_len-ro)length=remote[ri].iov_len-ro;
        uintptr_t address=(uintptr_t)remote[ri].iov_base;
        uintptr_t destination=(uintptr_t)local[li].iov_base;
        if(ro>INT64_MAX || address>(uintptr_t)INT64_MAX-ro ||
           lo>UINTPTR_MAX-destination || length>UINTPTR_MAX-(destination+lo)) {
            int error=EFAULT;close(fd);errno=error;return total?total:-1;
        }
        ssize_t n=pread(fd,(void *)(destination+lo),length,(off_t)(address+ro));
        if(n<0 && errno==EINTR)continue;
        if(n<=0){int error=n<0?errno:EIO;close(fd);errno=error;return total?total:-1;}
        total+=n;lo+=(size_t)n;ro+=(size_t)n;
        if((size_t)n<length)break;
    }
    close(fd);return total;
}

static bool armed,attempted;
static pid_t main_thread;
static struct timespec started;
static void maybe_init(void) {
    if(!armed || attempted || syscall(SYS_gettid)!=main_thread)return;
    struct timespec now;clock_gettime(CLOCK_MONOTONIC,&now);
    long elapsed=(now.tv_sec-started.tv_sec)*1000+(now.tv_nsec-started.tv_nsec)/1000000;
    const char *delay=getenv("ASTRBOT_COMPAT_INIT_DELAY_MS");if(elapsed<(delay?strtol(delay,NULL,10):5000))return;
    const char *module=getenv("ASTRBOT_COMPAT_WAIT_MODULE");
    const char *limit=getenv("ASTRBOT_COMPAT_INIT_TIMEOUT_MS");
    long max_wait=limit&&*limit?strtol(limit,NULL,10):180000;
    if(max_wait<1000)max_wait=180000;
    if(module && *module) {
        FILE *maps=fopen("/proc/self/maps","r");bool present=false;char line[4096];
        if(maps){while(fgets(line,sizeof(line),maps))if(strstr(line,module)){present=true;break;}fclose(maps);}
        if(!present){
            if(elapsed<max_wait)return;
            attempted=true;
            fprintf(stderr,"[guest-compat] timed out waiting for %s after %ld ms\n",module,elapsed);
            return;
        }
    }
    attempted=true;
    const char *library=getenv("ASTRBOT_COMPAT_INIT_LIBRARY"),*symbol=getenv("ASTRBOT_COMPAT_INIT_SYMBOL");
    void *handle=dlopen(library,RTLD_NOW|RTLD_GLOBAL);
    int (*initialize)(void)=handle?dlsym(handle,symbol):NULL;
    if(initialize)fprintf(stderr,"[guest-compat] main-thread initializer result=%d\n",initialize());
    else fprintf(stderr,"[guest-compat] main-thread initializer unavailable\n");
}
static bool init_pending(void) {
    return armed && !attempted && syscall(SYS_gettid)==main_thread;
}
static int64_t monotonic_ms(void) {
    struct timespec now;clock_gettime(CLOCK_MONOTONIC,&now);
    return (int64_t)now.tv_sec*1000+now.tv_nsec/1000000;
}
/* QQ can enter an idle event-loop wait before the delayed initializer is due.
 * Wake internally while it is pending, without returning synthetic timeouts to
 * Electron or running the initializer from a worker thread. Preserve the
 * caller's finite deadline, events, signal mask and EINTR result.
 */
static int compat_poll(int fd,struct epoll_event *events,int count,int timeout,
                       const sigset_t *mask,bool use_mask) {
    int (*wait_next)(int,struct epoll_event *,int,int)=dlsym(RTLD_NEXT,"epoll_wait");
    int (*pwait_next)(int,struct epoll_event *,int,int,const sigset_t *)=dlsym(RTLD_NEXT,"epoll_pwait");
    if(!init_pending())return use_mask?pwait_next(fd,events,count,timeout,mask):wait_next(fd,events,count,timeout);
    int64_t deadline=timeout>=0?monotonic_ms()+timeout:0;
    sigset_t original_mask;
    bool hold_mask=use_mask && mask;
    if(hold_mask) {
        int error=pthread_sigmask(SIG_SETMASK,mask,&original_mask);
        if(error){errno=error;return -1;}
    }
    int result,saved;
    for(;;) {
        maybe_init();
        int remaining=timeout;
        if(timeout>=0) {
            int64_t left=deadline-monotonic_ms();remaining=left>0?(int)left:0;
        }
        int slice=init_pending() && (remaining<0 || remaining>100)?100:remaining;
        result=use_mask?pwait_next(fd,events,count,slice,mask):wait_next(fd,events,count,slice);
        saved=errno;
        if(result<0)break;
        maybe_init();
        if(result || (timeout>=0 && monotonic_ms()>=deadline))break;
    }
    if(hold_mask)pthread_sigmask(SIG_SETMASK,&original_mask,NULL);
    errno=saved;return result;
}
int epoll_wait(int fd,struct epoll_event *events,int count,int timeout) {
    return compat_poll(fd,events,count,timeout,NULL,false);
}
int epoll_pwait(int fd,struct epoll_event *events,int count,int timeout,const sigset_t *mask) {
    return compat_poll(fd,events,count,timeout,mask,true);
}
__attribute__((constructor)) static void init(void) {
    const char *library=getenv("ASTRBOT_COMPAT_INIT_LIBRARY"),*symbol=getenv("ASTRBOT_COMPAT_INIT_SYMBOL");
    if(!library || !*library || !symbol || !*symbol)return;
    int fd=raw_open("/proc/self/cmdline",O_RDONLY|O_CLOEXEC,0);char args[8192];ssize_t length=fd>=0?read(fd,args,sizeof(args)):-1;if(fd>=0)close(fd);
    if(length<0 || length==(ssize_t)sizeof(args))return;
    const char *target=getenv("ASTRBOT_COMPAT_TARGET_NAME");
    const char *executable=strrchr(args,'/');executable=executable?executable+1:args;
    if(target && *target && strcmp(target,executable))return;
    const char *skip=getenv("ASTRBOT_COMPAT_SKIP_ARG_PREFIX");if(!skip)skip="--type=";
    if(*skip)for(ssize_t at=0;at<length;at+=strnlen(args+at,length-at)+1)if(!strncmp(args+at,skip,strlen(skip)))return;
    main_thread=syscall(SYS_gettid);clock_gettime(CLOCK_MONOTONIC,&started);armed=true;
}
