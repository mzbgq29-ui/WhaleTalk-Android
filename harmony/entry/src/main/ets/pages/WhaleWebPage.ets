import { webview } from '@kit.ArkWeb';
import { WhaleWebDestination, whaleWebTarget, isWhaleLocalWebUrl, whaleApi } from '../services/WhaleApi';

interface WebPageParams {
  destination: WhaleWebDestination;
}

@Entry
@Component
struct WhaleWebPage {
  @State title: string = '鲸语管理';
  @State pageUrl: string = 'http://127.0.0.1:6185/';
  @State loadError: string = '';
  @State credentialsVisible: boolean = false;
  @State credentialsBusy: boolean = false;
  @State credentialsText: string = '';
  private destination: WhaleWebDestination = 'dashboard';
  private controller: webview.WebviewController = new webview.WebviewController();

  aboutToAppear(): void {
    const params = this.getUIContext().getRouter().getParams() as WebPageParams;
    this.destination = params?.destination ?? 'dashboard';
    const target = whaleWebTarget(this.destination);
    this.title = target.title;
    this.pageUrl = target.url;
  }

  private async toggleCredentials(): Promise<void> {
    if (this.credentialsBusy) return;
    if (this.credentialsVisible) {
      this.credentialsVisible = false;
      this.credentialsText = '';
      return;
    }
    this.credentialsVisible = true;
    this.credentialsBusy = true;
    this.credentialsText = '正在读取登录信息…';
    try {
      const credentials = await whaleApi.credentials();
      if (this.destination === 'qq') {
        this.credentialsText = `登录画面密码：${credentials.vncPassword}\n连接画面后，使用你的 QQ 扫码登录。`;
      } else if (this.destination === 'snowluma') {
        this.credentialsText = `QQ 接入设置密码：${credentials.snowlumaPassword}`;
      } else {
        this.credentialsText = `用户名：${credentials.dashboardUsername}\n密码：${credentials.dashboardPassword}`;
      }
    } catch (err) {
      this.credentialsText = '暂时无法读取登录信息，请等待运行环境准备完成后重试。';
    } finally {
      this.credentialsBusy = false;
    }
  }

  build() {
    Column() {
      Row({ space: 12 }) {
        Button('返回', { buttonStyle: ButtonStyleMode.TEXTUAL })
          .onClick(() => this.getUIContext().getRouter().back())
        Text(this.title).fontSize(18).fontWeight(FontWeight.Bold).layoutWeight(1)
        Button(this.credentialsVisible ? '收起' : '登录信息', { buttonStyle: ButtonStyleMode.TEXTUAL })
          .enabled(!this.credentialsBusy)
          .onClick(() => { void this.toggleCredentials(); })
        Button('刷新', { buttonStyle: ButtonStyleMode.TEXTUAL })
          .onClick(() => {
            this.loadError = '';
            this.controller.refresh();
          })
      }.width('100%').padding({ left: 8, right: 8, top: 8, bottom: 8 })

      if (this.credentialsVisible) {
        Text(this.credentialsText)
          .fontSize(14).fontColor('#253B50').copyOption(CopyOptions.InApp)
          .padding(14).width('100%').backgroundColor('#E4F2F1')
      }

      if (this.loadError.length > 0) {
        Text(this.loadError)
          .fontSize(13).fontColor('#955222')
          .padding(12).width('100%').backgroundColor('#FFF2DF')
      }

      Web({ src: this.pageUrl, controller: this.controller })
        .javaScriptAccess(true)
        .domStorageAccess(true)
        .width('100%')
        .layoutWeight(1)
        .onPageEnd(() => { this.loadError = ''; })
        .onErrorReceive((event) => {
          if (event && event.request.isMainFrame()) {
            this.loadError = '页面暂时无法连接。首次启动需要等待服务准备完成，可返回首页查看状态。';
          }
        })
        .onOverrideUrlLoading((request) => {
          if (isWhaleLocalWebUrl(request.getRequestUrl())) {
            return false;
          }
          this.getUIContext().getPromptAction().showToast({ message: '此页面仅用于访问本机鲸语服务。' });
          return true;
        })
    }
    .width('100%').height('100%')
    .backgroundColor('#F4F7FA')
  }
}
