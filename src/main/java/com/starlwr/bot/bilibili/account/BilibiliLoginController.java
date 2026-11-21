package com.starlwr.bot.bilibili.account;

import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.QrCodeUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Bilibili 登录控制器
 */
@Controller
@StarBotComponent
@RequestMapping("/bilibili/login")
public class BilibiliLoginController {
    private static final String QR_CODE_PAGE = """
            <!DOCTYPE html>
            <html lang="zh">
            <head>
                <meta charset="UTF-8">
                <title>StarBot Bilibili 扫码登录</title>
            </head>
            <body>
            <div>
            %s
            </div>
            </body>
            </html>
            """;

    private final BilibiliAccountService accountService;

    @Autowired
    public BilibiliLoginController(BilibiliAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping(value = "/qrcode", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String qrCodeLogin() {
        String url = accountService.getLoginUrl();
        if (StringUtils.isBlank(url)) {
            return QR_CODE_PAGE.formatted("<h2 style=\"color:red;\">当前无需扫码登录</h2>");
        }

        String content = QrCodeUtil.generateQrCodeAndGetBase64(url, 500)
                .map("""
                        <h2>请使用 Bilibili APP 扫描以下二维码进行登录:</h2>
                        <img src="data:image/png;base64,%s" alt="二维码"/>
                        """::formatted)
                .orElse("<h2 style=\"color:red;\">生成二维码失败, 请检查日志</h2>");
        return QR_CODE_PAGE.formatted(content);
    }
}
