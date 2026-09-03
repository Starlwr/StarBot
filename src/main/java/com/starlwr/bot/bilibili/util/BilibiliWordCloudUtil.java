package com.starlwr.bot.bilibili.util;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.huaban.analysis.jieba.WordDictionary;
import com.starlwr.bot.core.plugin.StarBotComponent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bilibili 弹幕词云分词工具类
 */
@Slf4j
@StarBotComponent
public class BilibiliWordCloudUtil {
    private final Set<String> stopWords = new HashSet<>();

    private JiebaSegmenter segmenter;

    @PostConstruct
    public void init() {
        log.info("开始初始化弹幕词云分词工具");
        long startTime = System.currentTimeMillis();

        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            segmenter = new JiebaSegmenter();
            loadUserDictionary();
            loadStopWords();
            segmenter.process("init", JiebaSegmenter.SegMode.SEARCH);
        } finally {
            System.setOut(original);
        }

        log.info("弹幕词云分词工具初始化完成, 用时: {} 毫秒", System.currentTimeMillis() - startTime);
    }

    /**
     * 加载自定义词典
     */
    private void loadUserDictionary() {
        String path = "wordcloud/userdict.txt";
        try (InputStream inputStream = BilibiliWordCloudUtil.class.getClassLoader().getResourceAsStream(path)) {
            if (inputStream == null) {
                log.error("内置自定义词典不存在");
                return;
            }

            Path tempPath = Files.createTempFile("starbot-userdict", ".txt");
            try {
                Files.copy(inputStream, tempPath, StandardCopyOption.REPLACE_EXISTING);
                WordDictionary.getInstance().loadUserDict(tempPath, StandardCharsets.UTF_8);
            } finally {
                Files.deleteIfExists(tempPath);
            }
        } catch (Exception e) {
            log.error("加载内置自定义词典失败", e);
        }
    }

    /**
     * 加载停用词
     */
    private void loadStopWords() {
        String path = "wordcloud/stopword.txt";
        try (InputStream inputStream = BilibiliWordCloudUtil.class.getClassLoader().getResourceAsStream(path)) {
            if (inputStream == null) {
                log.error("内置停用词不存在");
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String word = line.trim();
                    if (!word.isEmpty()) {
                        stopWords.add(word);
                    }
                }
            }
        } catch (Exception e) {
            log.error("加载内置停用词失败", e);
        }
    }

    /**
     * 分词
     *
     * @param text 待分词文本
     * @return 去除停用词后的分词结果
     */
    public List<SegToken> segment(String text) {
        List<SegToken> tokens = segmenter.process(text, JiebaSegmenter.SegMode.SEARCH);
        if (stopWords.isEmpty()) {
            return tokens;
        }
        return tokens.stream().filter(token -> !stopWords.contains(token.word)).toList();
    }
}
