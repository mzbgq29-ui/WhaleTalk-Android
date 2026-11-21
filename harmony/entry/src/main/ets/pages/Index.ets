import { vmRuntime } from '../runtime/VmRuntime';
import { whaleApi, WhaleAction, WhaleServiceStatus, WhaleWebDestination } from '../services/WhaleApi';

@Entry
@Component
struct Index {
  @State vmPhase: string = 'missing';
  @State vmDetail: string = '点击启动，准备鲸语运行环境。';
  @State requiresRestart: boolean = false;
  @State memoryMiB: number = 2048;
  @State supervisorReady: boolean = false;
  @State astrbotState: string = '尚未连接';
  @State qqState: string = '尚未连接';
  @State configured: boolean = false;
  @State keepScreenOn: boolean = false;
  @State keepScreenBusy: boolean = false;
  @State busy: boolean = false;
  @State error: string = '';
  private timer: number = -1;
  private refreshing: boolean = false;
  private visible: boolean = false;

  onPageShow(): void {
    this.visible = true;
    this.stopPolling();
    void this.refreshStatus();
    this.timer = setInterval(() => { void this.refreshStatus(); }, 5000);
  }

  onPageHide(): void {
    this.visible = false;
    this.stopPolling();
  }

  aboutToDisappear(): void {
    this.visible = false;
    this.stopPolling();
  }

  private stopPolling(): void {
    if (this.timer >= 0) clearInterval(this.timer);
    this.timer = -1;
  }

  private syncVmState(): void {
    const state = vmRuntime.getState();
    this.vmPhase = state.phase;
    this.vmDetail = state.detail;
    this.requiresRestart = state.requiresRestart;
  }

  private serviceLabel(status: WhaleServiceStatus, isQq: boolean): string {
    if (status.ready) return isQq ? '接入服务已启动' : '服务就绪';
    if (status.state === 'running' || status.state === 'starting') {
      return isQq ? '等待登录或连接' : '正在准备';
    }
    if (status.state === 'failed') return '启动失败，请查看日志';
    if (status.state === 'stopped') return '已停止';
    return '状态未知';
  }

  private vmLabel(): string {
    switch (this.vmPhase) {
      case 'preparing': return '正在准备';
      case 'prepared':
      case 'ready': return '已准备';
      case 'starting': return '正在启动';
      case 'running': return '已启动';
      case 'stopping': return '正在关闭';
      case 'stopped': return '已关闭';
      case 'failed': return '发生错误';
      default: return '等待启动';
    }
  }

  private async refreshStatus(): Promise<void> {
    if (this.refreshing || !this.visible) return;
    this.refreshing = true;
    this.syncVmState();
    try {
      if (this.vmPhase !== 'running') {
        this.supervisorReady = false;
        this.astrbotState = '等待运行环境';
        this.qqState = '等待运行环境';
        return;
      }
      const status = await whaleApi.status();
      if (!this.visible) return;
      this.supervisorReady = true;
      this.configured = status.configured;
      this.astrbotState = this.serviceLabel(status.services.astrbot, false);
      this.qqState = this.serviceLabel(status.services.qq, true);
    } catch (err) {
      if (!this.visible) return;
      this.supervisorReady = false;
      this.astrbotState = '等待本机服务响应';
      this.qqState = '等待本机服务响应';
    } finally {
      this.refreshing = false;
      this.syncVmState();
    }
  }

  private async start(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    try {
      this.syncVmState();
      if (this.requiresRestart) {
        await vmRuntime.restartApplication();
        return;
      }
      if (this.vmPhase === 'running') {
        await whaleApi.control('all', 'start');
      } else {
        await vmRuntime.prepare();
        await vmRuntime.start(this.memoryMiB);
      }
      this.syncVmState();
      await this.refreshStatus();
    } catch (err) {
      this.syncVmState();
      this.error = err instanceof Error ? err.message : '启动失败，请查看运行日志。';
    } finally {
      this.busy = false;
    }
  }

  private async control(action: WhaleAction): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    try {
      await whaleApi.control('all', action);
      await this.refreshStatus();
    } catch (err) {
      this.error = err instanceof Error ? err.message : '操作失败，请稍后重试。';
    } finally {
      this.busy = false;
    }
  }

  private async shutdown(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    try {
      await vmRuntime.shutdown();
      this.syncVmState();
      this.supervisorReady = false;
    } catch (err) {
      this.error = err instanceof Error ? err.message : '关闭失败，请稍后重试。';
    } finally {
      this.busy = false;
    }
  }

  private async updateKeepScreenOn(enabled: boolean): Promise<void> {
    if (this.keepScreenBusy) return;
    const previous = this.keepScreenOn;
    this.keepScreenOn = enabled;
    this.keepScreenBusy = true;
    try {
      await vmRuntime.setKeepScreenOn(enabled);
    } catch (err) {
      this.keepScreenOn = previous;
      this.error = '无法设置屏幕常亮，请稍后重试。';
    } finally {
      this.keepScreenBusy = false;
    }
  }

  private openWeb(destination: WhaleWebDestination): void {
    this.getUIContext().getRouter().pushUrl({ url: 'pages/WhaleWebPage', params: { destination } });
  }

  @Builder
  statusRow(title: string, detail: string, ready: boolean) {
    Row({ space: 12 }) {
      Text('●').fontSize(12).fontColor(ready ? '#098C79' : '#A0AEBD')
      Text(title).fontSize(15).fontColor('#253B50').layoutWeight(1)
      Text(detail).fontSize(13).fontColor('#657A8F').textAlign(TextAlign.End)
    }.width('100%').padding({ top: 10, bottom: 10 })
  }

  @Builder
  featureButton(title: string, subtitle: string, destination: WhaleWebDestination) {
    Button({ type: ButtonType.Normal, buttonStyle: ButtonStyleMode.TEXTUAL }) {
      Column({ space: 7 }) {
        Text(title).fontSize(17).fontWeight(FontWeight.Medium).fontColor('#253B50')
        Text(subtitle).fontSize(12).fontColor('#6F8398')
      }.alignItems(HorizontalAlign.Start).width('100%').padding(16)
    }
    .layoutWeight(1).backgroundColor('#FFFFFF').borderRadius(18)
    .enabled(this.supervisorReady)
    .onClick(() => this.openWeb(destination))
  }

  build() {
    Scroll() {
      Column({ space: 18 }) {
        Row({ space: 12 }) {
          Text('鲸').fontSize(28).fontWeight(FontWeight.Bold).fontColor('#FFFFFF')
            .width(56).height(56).textAlign(TextAlign.Center)
            .backgroundColor('#007D80').borderRadius(20)
          Column({ space: 4 }) {
            Text('鲸语 WhaleTalk').fontSize(25).fontWeight(FontWeight.Bold).fontColor('#17354A')
            Text('让陪伴，融入日常').fontSize(13).fontColor('#6F8398')
          }.alignItems(HorizontalAlign.Start)
        }.width('100%').margin({ top: 12, bottom: 4 })

        Column({ space: 10 }) {
          Text('你的鲸语，正在这里').fontSize(20).fontWeight(FontWeight.Bold).fontColor('#17354A')
            .width('100%')
          Text(this.vmDetail).fontSize(14).fontColor('#6F8398').width('100%')
          Divider().color('#ECF1F5')
          this.statusRow('运行环境', this.vmLabel(), this.vmPhase === 'running')
          this.statusRow('聊天引擎', this.astrbotState, this.astrbotState === '服务就绪')
          this.statusRow('QQ 接入', this.qqState, this.qqState === '接入服务已启动')

          if (this.vmPhase !== 'running' && !this.requiresRestart) {
            Row() {
              Text('运行内存').fontSize(14).fontColor('#657A8F').layoutWeight(1)
              Select([{ value: '2 GB' }, { value: '4 GB' }])
                .selected(this.memoryMiB === 4096 ? 1 : 0)
                .value(this.memoryMiB === 4096 ? '4 GB' : '2 GB')
                .enabled(!this.busy)
                .onSelect((index: number) => { this.memoryMiB = index === 1 ? 4096 : 2048; })
            }.width('100%')
          }

          Button(this.busy ? '正在处理…' : this.requiresRestart ? '重新打开鲸语' : '启动鲸语')
            .width('100%').height(46).backgroundColor('#007D80').fontColor('#FFFFFF')
            .enabled(!this.busy && this.vmPhase !== 'stopping' &&
              (this.requiresRestart || this.vmPhase !== 'running' || this.supervisorReady))
            .onClick(() => { void this.start(); })
          if (this.vmPhase === 'running') {
            Row({ space: 12 }) {
              Button('重启机器人', { buttonStyle: ButtonStyleMode.TEXTUAL })
                .layoutWeight(1).enabled(!this.busy && this.supervisorReady)
                .onClick(() => { void this.control('restart'); })
              Button('停止机器人', { buttonStyle: ButtonStyleMode.TEXTUAL })
                .layoutWeight(1).enabled(!this.busy && this.supervisorReady)
                .onClick(() => { void this.control('stop'); })
            }
          }
        }.padding(20).width('100%').backgroundColor('#FFFFFF').borderRadius(24)

        Row({ space: 16 }) {
          Column({ space: 6 }) {
            Text('保持屏幕常亮').fontSize(15).fontWeight(FontWeight.Medium).fontColor('#253B50')
            Text('首次测试可开启。后台和锁屏运行需在设备上验证。')
              .fontSize(12).fontColor('#6F8398')
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: this.keepScreenOn })
            .selectedColor('#007D80')
            .enabled(!this.keepScreenBusy)
            .onChange((enabled: boolean) => { void this.updateKeepScreenOn(enabled); })
        }.padding(18).width('100%').backgroundColor('#FFFFFF').borderRadius(18)

        if (this.error.length > 0) {
          Text(this.error).fontSize(14).fontColor('#B24C32').width('100%')
            .padding(14).backgroundColor('#FFF0EB').borderRadius(14)
        }
        if (this.supervisorReady && !this.configured) {
          Text('首次使用，请登录 QQ，并在模型设置中填写你的 API。')
            .fontSize(14).fontColor('#5F748A').width('100%')
        }

        Row({ space: 12 }) {
          this.featureButton('QQ 登录', '扫码、查看登录画面', 'qq')
          this.featureButton('模型设置', '连接你的聊天模型', 'models')
        }.width('100%')
        Row({ space: 12 }) {
          this.featureButton('角色与人设', '管理角色和对话设定', 'personas')
          this.featureButton('完整管理', '插件、知识库与记录', 'dashboard')
        }.width('100%')

        Button('QQ 接入设置', { buttonStyle: ButtonStyleMode.TEXTUAL })
          .width('100%').enabled(this.supervisorReady)
          .onClick(() => this.openWeb('snowluma'))

        Row({ space: 12 }) {
          Button('运行日志', { buttonStyle: ButtonStyleMode.TEXTUAL })
            .layoutWeight(1)
            .onClick(() => {
              this.getUIContext().getRouter().pushUrl({ url: 'pages/WhaleLogsPage' });
            })
          Button('关闭运行环境', { buttonStyle: ButtonStyleMode.TEXTUAL })
            .layoutWeight(1).enabled(!this.busy && this.vmPhase === 'running')
            .onClick(() => { void this.shutdown(); })
        }.width('100%')
        Text('首次启动需要较长时间，请保持应用在前台。')
          .fontSize(12).fontColor('#8292A4').width('100%')
      }.padding(20).width('100%')
    }
    .width('100%').height('100%').backgroundColor('#F0F5F8')
    .scrollBar(BarState.Off)
  }
}
