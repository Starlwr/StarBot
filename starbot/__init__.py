import asyncio
import platform

# 如果系统为 Windows，则修改默认策略，以解决代理报错问题
if 'windows' in platform.system().lower():
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
