import { http } from '@kit.NetworkKit';

export interface WhaleServiceStatus {
  state: string;
  pid: number | null;
  ready: boolean;
}

export interface WhaleServices {
  astrbot: WhaleServiceStatus;
  qq: WhaleServiceStatus;
}

export interface WhalePorts {
  astrbot: number;
  qqWeb: number;
  rfb: number;
  snowWeb?: number;
}

export interface WhaleStatus {
  schema: number;
  configured: boolean;
  services: WhaleServices;
  ports: WhalePorts;
  uptimeSeconds: number;
}

export interface WhaleLogs {
  service: string;
  lines: string[];
  truncated: boolean;
}

export interface WhaleCredentials {
  dashboardUsername: string;
  dashboardPassword: string;
  vncPassword: string;
  snowlumaPassword: string;
}

export type WhaleService = 'all' | 'astrbot' | 'qq';
export type WhaleAction = 'start' | 'stop' | 'restart';
export type WhaleLogService = 'astrbot' | 'qq' | 'manager';
export type WhaleWebDestination = 'qq' | 'dashboard' | 'models' | 'personas' | 'snowluma';

export interface WhaleWebTarget {
  title: string;
  url: string;
}

export function whaleWebTarget(destination: WhaleWebDestination): WhaleWebTarget {
  switch (destination) {
    case 'qq':
      return { title: 'QQ 登录', url: 'http://127.0.0.1:6081/vnc.html?resize=scale&autoconnect=true' };
    case 'models':
      return { title: '模型设置', url: 'http://127.0.0.1:6185/#/providers' };
    case 'personas':
      return { title: '角色与人设', url: 'http://127.0.0.1:6185/#/persona' };
    case 'snowluma':
      return { title: 'QQ 接入设置', url: 'http://127.0.0.1:5099/' };
    default:
      return { title: '鲸语管理', url: 'http://127.0.0.1:6185/' };
  }
}

// The WebView has no native JavaScript bridge and opens only the local services.
export function isWhaleLocalWebUrl(url: string): boolean {
  return /^http:\/\/127\.0\.0\.1:(6081|6185|5099)(\/|$)/.test(url);
}

class WhaleApi {
  private async request(path: string, method: http.RequestMethod = http.RequestMethod.GET,
    body: string = ''): Promise<string> {
    const token = AppStorage.get<string>('whaletalk.apiToken') ?? '';
    if (token.length === 0) {
      throw new Error('运行环境尚未准备，请先启动鲸语。');
    }
    const client = http.createHttp();
    try {
      const response = await client.request(`http://127.0.0.1:8765${path}`, {
        method: method,
        header: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        },
        extraData: body,
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: 3000,
        readTimeout: 5000
      });
      if (response.responseCode === 401 || response.responseCode === 403) {
        throw new Error('本机服务认证失败，请关闭运行环境后重新启动。');
      }
      if (response.responseCode < 200 || response.responseCode >= 300) {
        throw new Error(`本机服务暂不可用（${response.responseCode}）。`);
      }
      return response.result as string;
    } finally {
      client.destroy();
    }
  }

  async status(): Promise<WhaleStatus> {
    return JSON.parse(await this.request('/api/v1/status')) as WhaleStatus;
  }

  async control(service: WhaleService, action: WhaleAction): Promise<void> {
    await this.request('/api/v1/control', http.RequestMethod.POST, JSON.stringify({ service, action }));
  }

  async logs(service: WhaleLogService): Promise<WhaleLogs> {
    return JSON.parse(await this.request(`/api/v1/logs?service=${service}&lines=100`)) as WhaleLogs;
  }

  async credentials(): Promise<WhaleCredentials> {
    return JSON.parse(await this.request('/api/v1/credentials')) as WhaleCredentials;
  }
}

export const whaleApi: WhaleApi = new WhaleApi();
