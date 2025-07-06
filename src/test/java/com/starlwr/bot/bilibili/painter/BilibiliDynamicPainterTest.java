package com.starlwr.bot.bilibili.painter;

import com.alibaba.fastjson2.JSON;
import com.starlwr.bot.bilibili.config.TestBuildPropertiesConfig;
import com.starlwr.bot.bilibili.config.TestContextConfig;
import com.starlwr.bot.bilibili.factory.BilibiliDynamicPainterFactory;
import com.starlwr.bot.bilibili.model.Dynamic;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Import(TestBuildPropertiesConfig.class)
@ContextConfiguration(classes = TestContextConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class BilibiliDynamicPainterTest {
    @Resource
    private BilibiliDynamicPainterFactory factory;

    /**
     * 从日志文件中读取动态信息生成图片
     */
    @Test
    public void testPaintDynamicFromDebugLogFile() throws IOException {
        String logFilePath = "DynamicDebug/DYNAMIC_TYPE_DRAW.log";
        int linesToRead = 10;
        String filter = null;
        String outputDir = "TestDynamic";

        Path outputPath = Paths.get(outputDir);
        if (!Files.exists(outputPath)) {
            Files.createDirectories(outputPath);
        }

        List<String> latestLines = readLatestLines(logFilePath, linesToRead, filter);

        List<Dynamic> dynamics = extractDynamicsFromLogLines(latestLines);

        for (Dynamic dynamic : dynamics) {
            BilibiliDynamicPainter painter = factory.create(dynamic);

            String outputFilePath = outputDir + "/" + dynamic.getId() + ".png";
            painter.paint(outputFilePath);
        }
    }

    /**
     * 读取文件的最新 n 行
     * @param filePath 文件路径
     * @param n 要读取的行数
     * @return 最新 n 行内容
     */
    private List<String> readLatestLines(String filePath, int n, String filter) throws IOException {
        Path path = Paths.get(filePath);
        List<String> allLines = Files.readAllLines(path);

        if (allLines.size() <= n) {
            if (filter == null) {
                return allLines;
            } else {
                return allLines.stream().filter(line -> line.matches(filter)).collect(Collectors.toList());
            }
        }

        if (filter == null) {
            return allLines.subList(allLines.size() - n, allLines.size());
        } else {
            return allLines.subList(allLines.size() - n, allLines.size()).stream().filter(line -> line.matches(filter)).collect(Collectors.toList());
        }
    }

    /**
     * 从日志中提取动态对象
     * @param logLines 日志行
     * @return 动态列表
     */
    private List<Dynamic> extractDynamicsFromLogLines(List<String> logLines) {
        List<Dynamic> dynamics = new ArrayList<>();

        for (String line : logLines) {
            String jsonStr = line.split(": ")[1];

            try {
                Dynamic dynamic = JSON.parseObject(jsonStr, Dynamic.class);
                dynamics.add(dynamic);
            } catch (Exception e) {
                System.err.println("解析 JSON 出错: " + e.getMessage());
            }
        }

        return dynamics;
    }
}
