/** Android Web 先打开工作台，设备工具在首个智能体发布前完成注册。 */
export function mountAndroidMcp(ctx, config, effectName, registerProvider) {
  const owner = ctx.fiber.uid;
  async function start() {
    const McpClient = await import('@deepseek-ai/dsh-mcp-client');
    if (owner === null || ctx.fiber.uid !== owner) return;
    let child;
    const dispose = ctx.effect(function* () {
      if (registerProvider) yield registerProvider();
      child = ctx.plugin(McpClient, McpClient.Config(config));
      yield child.dispose;
    }, effectName);
    try { await child.await(); }
    catch (error) { await dispose(); throw error; }
  }

  const agents = ctx.get('agents');
  const androidWeb = ctx.get('profileContext')?.name === 'web'
    && process.env.DSHA_STARTUP_PROFILE === 'web'
    && /^[1-9][0-9]*$/.test(process.env.DSHA_WEB_GENERATION || '');
  // 热重载时已有智能体可能立即使用工具，沿用正常的加载屏障。
  if (!androidWeb || agents?.list().length) return start();

  let readiness;
  ctx.on('agent/created', () => {
    readiness ??= Promise.resolve().then(() => {
      const registry = ctx.get('agents');
      return registry ? registry.withoutInitiator(start) : start();
    }).catch(error => {
      // 设备插件故障仍可诊断，普通对话可以继续。
      ctx.logger.error(`${config.serverName}: ${String(error)}`);
    });
    return readiness;
  });
}
