import { mountAndroidMcp } from '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/dsha-android-mcp-start.mjs';
import { ComputerUseProviderName } from '@deepseek-ai/dsh-computer-use/brand';
import { fileURLToPath } from 'node:url';

export const name = 'dsh-computer-use-android';
export const inject = ['computerUse', 'tools'];
export async function apply(ctx) {
  return mountAndroidMcp(ctx, {transport:'stdio',serverName:'dsha-android',
    command:process.execPath,args:[fileURLToPath(new URL('./server.cjs',import.meta.url))],
    failOnStartupError:true,toolCallTimeoutMs:180000,reconnect:{enabled:false}}, 'dsha-android-computer-use',
    () => ctx.computerUse.register(ComputerUseProviderName('dsha-android')));
}
