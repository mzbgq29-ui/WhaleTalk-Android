import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { vmRuntime } from '../runtime/VmRuntime';

export default class EntryAbility extends UIAbility {
  onCreate(_want: Want, _launchParam: AbilityConstant.LaunchParam): void {
    vmRuntime.attach(this.context);
  }

  onWindowStageCreate(stage: window.WindowStage): void {
    const mainWindow = stage.getMainWindowSync();
    AppStorage.setOrCreate('whaletalk.mainWindow', mainWindow);
    stage.loadContent('pages/Index');
  }
}
