package com.clandropswebhook;

import lombok.Data;

/**
 * Class used from:
 * https://github.com/ATremonte/Discord-Level-Notifications/
 */
@Data
public class DiscordWebHookBody {
    private String content;
    private Embed embed;

    @Data
    static class Embed
    {
        final UrlEmbed image;
    }

    @Data
    static class UrlEmbed
    {
        final String url;
    }
}
