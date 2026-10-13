package com.starlwr.bot.bilibili.account;

import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.QrCodeUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Optional;

@Slf4j
@Controller
@StarBotComponent
@RequestMapping("/bilibili/login")
public class BilibiliLoginController {
    private final BilibiliAccountService accountService;

    @Autowired
    public BilibiliLoginController(BilibiliAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/qrcode")
    public String qrCodeLogin(Model model) {
        String url = accountService.getLoginUrl();

        if (StringUtils.isNotBlank(url)) {
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
