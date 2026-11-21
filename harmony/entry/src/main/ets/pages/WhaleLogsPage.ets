import { whaleApi, WhaleLogService } from '../services/WhaleApi';
import { vmRuntime } from '../runtime/VmRuntime';

type WhaleLogTab = WhaleLogService | 'boot';

@Entry
@Component
struct WhaleLogsPage {
  @State service: WhaleLogTab = 'manager';
  @State content: string = '';
  @State error: string = '';
  @State busy: boolean = false;
  @State logSource: string = '运行环境';

  aboutToAppear(): void {
    void this.refreshLogs();
  }

  private async refreshLogs(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    try {
      if (this.service === 'boot' || vmRuntime.getState().phase !== 'running') {
        await this.readBootLog(this.service === 'boot' ? '' : '本机服务尚未启动，下面显示环境启动日志。');
        return;
      }
      const result = await whaleApi.logs(this.service);
      this.content = result.lines.join('\n');
      this.logSource = this.service === 'qq' ? 'QQ 接入' : this.service === 'astrbot' ? '聊天引擎' : '运行环境';
      if (this.content.length === 0) this.content = '暂无日志。';
    } catch (err) {
      try {
        await this.readBootLog('本机服务暂未响应，下面显示环境启动日志。');
      } catch (bootError) {
        this.error = '暂时无法读取日志，请返回首页确认运行环境状态后重试。';
        this.content = '';
      }
    } finally {
      this.busy = false;
    }
  }

  private async readBootLog(reason: string): Promise<void> {
    const bootLog = await vmRuntime.getBootLog();
    this.content = bootLog.length > 0 ? bootLog.slice(-65536) : '暂无启动日志。启动鲸语后可在这里查看准备进度。';
    this.logSource = '环境启动日志';
    this.error = reason;
  }

  private async selectService(service: WhaleLogTab): Promise<void> {
    if (this.busy) return;
    this.service = service;
    await this.refreshLogs();
  }

  build() {
    Column({ space: 12 }) {
      Row() {
        Button('返回', { buttonStyle: ButtonStyleMode.TEXTUAL })
          .onClick(() => this.getUIContext().getRouter().back())
        Text('运行日志').fontSize(20).fontWeight(FontWeight.Bold).layoutWeight(1)
        Button(this.busy ? '读取中' : '刷新', { buttonStyle: ButtonStyleMode.TEXTUAL })
          .enabled(!this.busy).onClick(() => { void this.refreshLogs(); })
      }.width('100%')

      Scroll() {
        Row({ space: 4 }) {
          Button('启动日志', { buttonStyle: ButtonStyleMode.TEXTUAL })
            .fontColor(this.service === 'boot' ? '#007D80' : '#64758B')
            .enabled(!this.busy).onClick(() => { void this.selectService('boot'); })
          Button('运行环境', { buttonStyle: ButtonStyleMode.TEXTUAL })
            .fontColor(this.service === 'manager' ? '#007D80' : '#64758B')
            .enabled(!this.busy).onClick(() => { void this.selectService('manager'); })
          Button('QQ 接入', { buttonStyle: ButtonStyleMode.TEXTUAL })
            .fontColor(this.service === 'qq' ? '#007D80' : '#64758B')
            .enabled(!this.busy).onClick(() => { void this.selectService('qq'); })
          Button('聊天引擎', { buttonStyle: ButtonStyleMode.TEXTUAL })
            .fontColor(this.service === 'astrbot' ? '#007D80' : '#64758B')
            .enabled(!this.busy).onClick(() => { void this.selectService('astrbot'); })
        }
      }.width('100%').scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off)

      if (this.error.length > 0) {
        Text(this.error).fontColor('#B24C32').fontSize(14).width('100%')
      }
      Text(this.logSource).fontSize(12).fontColor('#657A8F').width('100%')
      Scroll() {
        Text(this.content)
          .fontFamily('monospace').fontSize(12).fontColor('#D3DEE9')
          .copyOption(CopyOptions.InApp)
          .width('100%').padding(14)
      }
      .width('100%').layoutWeight(1)
      .backgroundColor('#142333').borderRadius(16)
    }
    .padding(16).width('100%').height('100%').backgroundColor('#F4F7FA')
  }
}
