import { resourceManager } from '@kit.LocalizationKit';
export const copyRawFile: (resources: resourceManager.ResourceManager, name: string, destination: string) => Promise<number>;
export const startVM: (argsLines: string) => boolean;
export const getVMStatus: () => number;
export const getVMError: () => string;
