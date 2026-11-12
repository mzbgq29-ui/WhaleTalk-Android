#include <dlfcn.h>
#include <stdio.h>

/* Exercise the vendor's public startup/socket exports; no QQ account or packet
 * send is involved. The original vendor library remains a separate input. */
int main(int argc,char **argv) {
    if(argc!=2)return 2;
    void *library=dlopen(argv[1],RTLD_NOW|RTLD_GLOBAL);
    if(!library){fprintf(stderr,"%s\n",dlerror());return 1;}
    int (*start)(void)=dlsym(library,"snowluma_linux_hook_start_dynamic");
    int (*stop)(void)=dlsym(library,"snowluma_linux_hook_stop_dynamic");
    const char *(*control)(void)=dlsym(library,"snowluma_linux_hook_control_socket");
    const char *(*recv)(void)=dlsym(library,"snowluma_linux_hook_recv_socket");
    if(!start || !stop || !control || !recv)return 3;
    int result=start();
    printf("%d\n%s\n%s\n",result,control(),recv());fflush(stdout);
    (void)getchar();
    (void)stop();
    return result==1?0:4;
}
