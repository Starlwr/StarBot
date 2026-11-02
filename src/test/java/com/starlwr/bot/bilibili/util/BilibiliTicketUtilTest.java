package com.starlwr.bot.bilibili.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.starlwr.bot.bilibili.api.sign.BilibiliTicketUtil;

class BilibiliTicketUtilTest {
    @Test
    void ticketRequestUsesCurrentCsrfLikeBilibiliWeb() {
        String url = BilibiliTicketUtil.getBilibiliTicketUrl("current-csrf");

        assertTrue(url.contains("/bilibili.api.ticket.v1.Ticket/GenWebTicket"));
        assertTrue(url.endsWith("&csrf=current-csrf"));
    }
}
