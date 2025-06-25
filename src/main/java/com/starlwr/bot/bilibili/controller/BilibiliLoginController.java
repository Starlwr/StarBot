package com.starlwr.bot.bilibili.controller;

import com.starlwr.bot.bilibili.service.BilibiliAccountService;
import com.starlwr.bot.core.util.QrCodeUtil;
import com.starlwr.bot.core.util.StringUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Optional;

@Slf4j
@Controller
@RequestMapping("/bilibili/login")
public class BilibiliLoginController {
    @Resource
    private BilibiliAccountService accountService;

    @GetMapping("/qrcode")
    public String qrCodeLogin(Model model) {
        String url = accountService.getLoginUrl();

        if (StringUtil.isNotBlank(url)) {
            Optional<String> optionalBase64 = QrCodeUtil.generateQrCodeAndGetBase64(url, 500);
            optionalBase64.ifPresentOrElse(
                    base64 -> model.addAttribute("base64", base64),
                    () -> model.addAttribute("error", "生成二维码失败, 请检查日志")
            );
        } else {
            model.addAttribute("error", "当前无需扫码登录");
        }

        return "qrcode";
    }
}
