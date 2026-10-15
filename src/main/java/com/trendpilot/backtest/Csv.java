package com.trendpilot.backtest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** 回测明细导出（gzip 压缩的 CSV，方便核对）。 */
final class Csv {

    private Csv() {
    }

    static void writeGz(Path file, String header, List<String> lines) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8))) {
            w.write(header);
            w.newLine();
            for (String l : lines) {
                w.write(l);
                w.newLine();
            }
        }
    }
}
