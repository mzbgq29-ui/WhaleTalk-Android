import { common } from '@kit.AbilityKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { window } from '@kit.ArkUI';
import nativeVm from 'libwhaletalk_vm.so';

export interface VmState {
  phase: string;
  detail: string;
  memoryMiB: number;
}

interface BootstrapConfig {
  apiToken: string;
}

class VmRuntime {
  private context: common.UIAbilityContext | undefined = undefined;
  private phase: string = 'missing';
  private detail: string = '点击启动，准备鲸语运行环境。';
  private memoryMiB: number = 4096;
  private preparation: Promise<void> | undefined = undefined;
  private token: string = '';

  attach(context: common.UIAbilityContext): void {
    this.context = context;
  }

  private getContext(): common.UIAbilityContext {
    if (this.context === undefined) throw new Error('鲸语尚未完成初始化，请重新打开应用。');
    return this.context;
  }

  getState(): VmState {
    const nativeState = nativeVm.getVMStatus();
    if (nativeState === 2) {
      this.phase = 'stopped';
      this.detail = '运行环境已关闭。再次启动前请关闭鲸语并重新打开。';
    } else if (nativeState === 3) {
      this.phase = 'failed';
      this.detail = nativeVm.getVMError();
    }
    return { phase: this.phase, detail: this.detail, memoryMiB: this.memoryMiB };
  }

  async prepare(): Promise<void> {
    if (this.preparation !== undefined) return this.preparation;
    if (nativeVm.getVMStatus() !== 0) {
      if (nativeVm.getVMStatus() === 1) return;
      throw new Error('请关闭鲸语并重新打开后，再启动运行环境。');
    }
    const operation = this.prepareResources();
    this.preparation = operation;
    try {
      await operation;
    } catch (error) {
      this.phase = 'failed';
      this.detail = error instanceof Error ? error.message : '运行资源准备失败，请检查可用存储空间。';
      throw new Error(this.detail);
    } finally {
      this.preparation = undefined;
    }
  }

  private async prepareResources(): Promise<void> {
    const context = this.getContext();
    this.phase = 'preparing';
    this.detail = '正在准备本机运行资源。首次启动需要复制系统镜像，请保持鲸语在前台。';
    const vmDirectory = `${context.filesDir}/vm`;
    const shareDirectory = `${context.filesDir}/share`;
    for (const path of [vmDirectory, shareDirectory]) {
      if (!fs.accessSync(path)) fs.mkdirSync(path, true);
    }
    const configPath = `${shareDirectory}/whaletalk-bootstrap.json`;
    if (fs.accessSync(configPath)) {
      const previous = JSON.parse(fs.readTextSync(configPath)) as BootstrapConfig;
      if (previous.apiToken.length >= 32) this.token = previous.apiToken;
    }
    if (this.token.length === 0) {
      const bytes = cryptoFramework.createRandom().generateRandomSync(32).data;
      this.token = Array.from(bytes).map((value: number): string => value.toString(16).padStart(2, '0')).join('');
      const config: BootstrapConfig = { apiToken: this.token };
      const temporary = `${configPath}.installing`;
      const file = fs.openSync(temporary, fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
      try { fs.writeSync(file.fd, JSON.stringify(config)); } finally { fs.closeSync(file); }
      fs.chmodSync(temporary, 0o600);
      fs.renameSync(temporary, configPath);
    }
    AppStorage.setOrCreate('whaletalk.apiToken', this.token);

    // Never replace the writable system disk: it contains the user's QQ session and settings.
    const diskPath = `${vmDirectory}/rootfs_aarch64.qcow2`;
    if (!fs.accessSync(diskPath)) {
      this.detail = '正在复制鲸语系统镜像。首次准备可能需要几分钟，请保持应用在前台。';
      await nativeVm.copyRawFile(context.resourceManager, 'vm/rootfs_aarch64.qcow2', diskPath);
    }
    const kernelPath = `${vmDirectory}/kernel_aarch64`;
    if (!fs.accessSync(kernelPath)) {
      this.detail = '正在准备 Linux 内核。';
      await nativeVm.copyRawFile(context.resourceManager, 'vm/kernel_aarch64', kernelPath);
    }
    if (fs.statSync(diskPath).size < 1048576 || fs.statSync(kernelPath).size < 1048576) {
      throw new Error('运行资源不完整。请保留应用数据并重新安装完整安装包。');
    }
    this.phase = 'prepared';
    this.detail = '运行资源已准备，可以启动。';
  }

  async start(memoryMiB: number): Promise<void> {
    const context = this.getContext();
    if (this.phase !== 'prepared') await this.prepare();
    if (nativeVm.getVMStatus() === 1) return;
    if (memoryMiB !== 2048 && memoryMiB !== 4096) throw new Error('请选择 2 GB 或 4 GB 运行内存。');
    this.memoryMiB = memoryMiB;
    this.phase = 'starting';
    this.detail = '正在启动本机运行环境。首次载入 QQ 和聊天引擎需要等待。';
    const root = `${context.filesDir}/vm`;
    const ports: number[] = [8765, 6185, 6081, 5099];
    const network = 'user,id=net0' + ports.map((port: number): string => `,hostfwd=tcp:127.0.0.1:${port}-:${port}`).join('');
    const args: string[] = [
      'qemu-system-aarch64', '-machine', 'virt', '-cpu', 'max',
      '-accel', 'tcg,thread=multi,tb-size=128', '-smp', '2', '-m', memoryMiB.toString(),
      '-kernel', `${root}/kernel_aarch64`,
      '-append', 'root=/dev/sda rw rootfstype=ext4 rootwait console=ttyAMA0 net.ifnames=0 init=/init',
      '-drive', `if=none,id=disk0,format=qcow2,file=${root}/rootfs_aarch64.qcow2,cache=writeback`,
      '-device', 'virtio-scsi-pci,id=scsi0', '-device', 'scsi-hd,drive=disk0,bus=scsi0.0',
      '-netdev', network, '-device', 'virtio-net-pci,netdev=net0',
      '-fsdev', `local,id=share,path=${context.filesDir}/share,security_model=none`,
      '-device', 'virtio-9p-pci,fsdev=share,mount_tag=hostshare',
      '-device', 'virtio-rng-pci', '-serial', `file:${root}/serial.log`,
      '-monitor', 'none', '-display', 'none', '-no-reboot'
    ];
    if (!nativeVm.startVM(args.join('\n'))) {
      this.phase = 'failed';
      this.detail = nativeVm.getVMError();
      throw new Error(this.detail);
    }
    this.phase = 'running';
    this.detail = '运行环境已启动，正在等待 QQ 和聊天引擎响应。';
  }

  async shutdown(): Promise<void> {
    if (nativeVm.getVMStatus() !== 1) return;
    const client = http.createHttp();
    try {
      const response = await client.request('http://127.0.0.1:8765/api/v1/shutdown', {
        method: http.RequestMethod.POST,
        header: { 'Authorization': `Bearer ${this.token}`, 'Content-Type': 'application/json' },
        extraData: '{}', connectTimeout: 3000, readTimeout: 10000
      });
      if (response.responseCode !== 200 && response.responseCode !== 202) throw new Error('运行环境未能接受关闭请求。');
      this.phase = 'stopping';
      this.detail = '正在保存数据并关闭运行环境，请保持应用在前台。';
    } finally {
      client.destroy();
    }
  }

  async getBootLog(): Promise<string> {
    const path = `${this.getContext().filesDir}/vm/serial.log`;
    if (!fs.accessSync(path)) return '运行环境尚未输出启动日志。';
    const file = await fs.open(path, fs.OpenMode.READ_ONLY);
    try {
      const info = await fs.stat(path);
      const bytes = new ArrayBuffer(Math.min(info.size, 65536));
      const count = await fs.read(file.fd, bytes, { offset: Math.max(0, info.size - bytes.byteLength), length: bytes.byteLength });
      return util.TextDecoder.create('utf-8', { ignoreBOM: true }).decodeWithStream(new Uint8Array(bytes, 0, count));
    } finally {
      await fs.close(file);
    }
  }

  async setKeepScreenOn(enabled: boolean): Promise<void> {
    const mainWindow = AppStorage.get<window.Window>('whaletalk.mainWindow');
    if (mainWindow !== undefined) await mainWindow.setWindowKeepScreenOn(enabled);
  }
}

export const vmRuntime: VmRuntime = new VmRuntime();
