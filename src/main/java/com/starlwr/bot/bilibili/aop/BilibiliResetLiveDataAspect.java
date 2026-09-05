package com.starlwr.bot.bilibili.aop;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 开播时重置粉丝数、粉丝勋章数、大航海
 */
@Slf4j
@Aspect
@StarBotComponent
public class BilibiliResetLiveDataAspect {
    private final LiveDataService liveDataService;

    @Autowired
    public BilibiliResetLiveDataAspect(LiveDataService liveDataService) {
        this.liveDataService = liveDataService;
    }

    @Pointcut("execution(* com.starlwr.bot.core.service.LiveDataService.resetLiveData(..))")
    public void resetLiveDataPointcut() {
    }

    @Around("resetLiveDataPointcut()")
    public Object aroundResetLiveDataMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        Object result = joinPoint.proceed();

        String platform = LivePlatform.BILIBILI.getName();
        String uid = String.valueOf(joinPoint.getArgs()[1]);

        liveDataService.deleteCustomObject(platform, "BeforeFansCount", uid);
        liveDataService.deleteCustomObject(platform, "BeforeFansMedalCount", uid);
        liveDataService.deleteCustomObject(platform, "BeforeGuardCount", uid);
        liveDataService.deleteCustomObject(platform, "AfterFansCount", uid);
        liveDataService.deleteCustomObject(platform, "AfterFansMedalCount", uid);
        liveDataService.deleteCustomObject(platform, "AfterGuardCount", uid);

        return result;
    }
}
