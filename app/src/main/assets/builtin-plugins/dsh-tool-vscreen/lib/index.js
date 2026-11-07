import { mountAndroidMcp } from '/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/dsha-android-mcp-start.mjs';
import { fileURLToPath } from 'node:url';

export const name = 'dsh-tool-vscreen';
export const inject = ['tools'];
export async function apply(ctx) {
  return mountAndroidMcp(ctx, {transport:'stdio',serverName:'dsha-vscreen',
    command:process.execPath,args:[fileURLToPath(new URL('./server.cjs',import.meta.url))],
    failOnStartupError:true,toolCallTimeoutMs:60000,reconnect:{enabled:false}}, 'dsha-vscreen-tools');
}
