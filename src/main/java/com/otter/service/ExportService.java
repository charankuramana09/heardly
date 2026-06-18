package com.otter.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.List;
import com.lowagie.text.pdf.PdfWriter;
import com.otter.domain.Insight;
import com.otter.domain.Recording;
import com.otter.domain.Transcript;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

@Service
public class ExportService {

    private final ObjectMapper objectMapper;
    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' HH:mm z").withZone(ZoneId.systemDefault());

    public ExportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== DOCX ====================

    public byte[] exportDocx(Recording recording, Transcript transcript, Insight insight) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addDocxTitle(doc, displayTitle(recording, insight));
            if (insight != null && insight.getSmartTitle() != null && recording.getOriginalFilename() != null) {
                addDocxLine(doc, recording.getOriginalFilename(), 10, true, "808080");
            }
            addDocxMeta(doc, recording, transcript);

            if (insight != null && insight.getStatus() != null && insight.getStatus().name().equals("COMPLETED")) {
                if (notBlank(insight.getSummary())) {
                    addDocxHeading(doc, "Summary");
                    addDocxParagraph(doc, insight.getSummary());
                }
                var actions = parseActionItems(insight.getActionItemsJson());
                if (!actions.isEmpty()) {
                    addDocxHeading(doc, "Action items");
                    for (var a : actions) {
                        String task = String.valueOf(a.getOrDefault("task", ""));
                        Object owner = a.get("owner");
                        Object due = a.get("due");
                        StringBuilder line = new StringBuilder("• ").append(task);
                        if (owner != null && !"null".equals(String.valueOf(owner))) line.append("  — ").append(owner);
                        if (due != null && !"null".equals(String.valueOf(due))) line.append("  (").append(due).append(")");
                        addDocxParagraph(doc, line.toString());
                    }
                }
                var topics = parseStrings(insight.getKeyTopicsJson());
                if (!topics.isEmpty()) {
                    addDocxHeading(doc, "Key topics");
                    addDocxParagraph(doc, String.join("  ·  ", topics));
                }
                var decisions = parseStrings(insight.getDecisionsJson());
                if (!decisions.isEmpty()) {
                    addDocxHeading(doc, "Decisions");
                    for (String d : decisions) addDocxParagraph(doc, "• " + d);
                }
            }

            addDocxHeading(doc, "Transcript");
            if (transcript != null) {
                var segments = parseSegments(transcript.getSegmentsJson());
                if (segments.isEmpty() && notBlank(transcript.getFullText())) {
                    addDocxParagraph(doc, transcript.getFullText());
                } else {
                    for (var seg : segments) {
                        double start = toDouble(seg.get("start"));
                        double end = toDouble(seg.get("end"));
                        String text = String.valueOf(seg.getOrDefault("text", "")).trim();
                        XWPFParagraph p = doc.createParagraph();
                        XWPFRun time = p.createRun();
                        time.setText(formatTime(start) + " → " + formatTime(end));
                        time.setColor("9CA3AF");
                        time.setBold(false);
                        time.setFontFamily("Courier New");
                        time.setFontSize(9);
                        XWPFRun txt = p.createRun();
                        txt.addTab();
                        txt.setText(text);
                        txt.setFontSize(11);
                    }
                }
            }

            doc.write(out);
            return out.toByteArray();
        }
    }

    private void addDocxTitle(XWPFDocument doc, String title) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(title);
        r.setBold(true);
        r.setFontSize(20);
    }

    private void addDocxHeading(XWPFDocument doc, String text) {
        XWPFParagraph spacer = doc.createParagraph();
        spacer.createRun().setText(" ");

        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(true);
        r.setFontSize(13);
        r.setColor("4F46E5");
    }

    private void addDocxParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontSize(11);
    }

    private void addDocxLine(XWPFDocument doc, String text, int size, boolean italic, String hex) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontSize(size);
        r.setItalic(italic);
        r.setColor(hex);
    }

    private void addDocxMeta(XWPFDocument doc, Recording recording, Transcript transcript) {
        StringBuilder meta = new StringBuilder();
        if (recording.getCreatedAt() != null) {
            meta.append(DATE_FMT.format(recording.getCreatedAt()));
        }
        if (transcript != null && transcript.getDurationSeconds() != null) {
            if (meta.length() > 0) meta.append("  ·  ");
            meta.append((int) Math.round(transcript.getDurationSeconds() / 60.0)).append(" min");
        }
        if (transcript != null && notBlank(transcript.getLanguage())) {
            if (meta.length() > 0) meta.append("  ·  ");
            meta.append(transcript.getLanguage().toUpperCase());
        }
        if (recording.getSizeBytes() != null) {
            if (meta.length() > 0) meta.append("  ·  ");
            meta.append(String.format("%.1f MB", recording.getSizeBytes() / 1_048_576.0));
        }
        if (meta.length() > 0) addDocxLine(doc, meta.toString(), 10, false, "6B7280");
    }

    // ==================== PDF ====================

    public byte[] exportPdf(Recording recording, Transcript transcript, Insight insight) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            com.lowagie.text.Document doc = new com.lowagie.text.Document(PageSize.LETTER, 56, 56, 56, 56);
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font titleFont    = new Font(Font.HELVETICA, 20, Font.BOLD, new Color(24, 24, 27));
            Font subtitleFont = new Font(Font.HELVETICA, 10, Font.ITALIC, new Color(128, 128, 128));
            Font metaFont     = new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(107, 114, 128));
            Font headingFont  = new Font(Font.HELVETICA, 13, Font.BOLD, new Color(79, 70, 229));
            Font bodyFont     = new Font(Font.HELVETICA, 11, Font.NORMAL, new Color(24, 24, 27));
            Font monoFont     = new Font(Font.COURIER, 9, Font.NORMAL, new Color(156, 163, 175));

            doc.add(new Paragraph(displayTitle(recording, insight), titleFont));
            if (insight != null && insight.getSmartTitle() != null && recording.getOriginalFilename() != null) {
                doc.add(new Paragraph(recording.getOriginalFilename(), subtitleFont));
            }
            StringBuilder meta = new StringBuilder();
            if (recording.getCreatedAt() != null) meta.append(DATE_FMT.format(recording.getCreatedAt()));
            if (transcript != null && transcript.getDurationSeconds() != null) {
                if (meta.length() > 0) meta.append("  ·  ");
                meta.append((int) Math.round(transcript.getDurationSeconds() / 60.0)).append(" min");
            }
            if (transcript != null && notBlank(transcript.getLanguage())) {
                if (meta.length() > 0) meta.append("  ·  ");
                meta.append(transcript.getLanguage().toUpperCase());
            }
            if (recording.getSizeBytes() != null) {
                if (meta.length() > 0) meta.append("  ·  ");
                meta.append(String.format("%.1f MB", recording.getSizeBytes() / 1_048_576.0));
            }
            if (meta.length() > 0) doc.add(new Paragraph(meta.toString(), metaFont));
            doc.add(Chunk.NEWLINE);

            if (insight != null && insight.getStatus() != null && insight.getStatus().name().equals("COMPLETED")) {
                if (notBlank(insight.getSummary())) {
                    addPdfHeading(doc, "Summary", headingFont);
                    doc.add(new Paragraph(insight.getSummary(), bodyFont));
                }
                var actions = parseActionItems(insight.getActionItemsJson());
                if (!actions.isEmpty()) {
                    addPdfHeading(doc, "Action items", headingFont);
                    List list = new List(false, false, 12);
                    for (var a : actions) {
                        String task = String.valueOf(a.getOrDefault("task", ""));
                        Object owner = a.get("owner");
                        Object due = a.get("due");
                        StringBuilder line = new StringBuilder(task);
                        if (owner != null && !"null".equals(String.valueOf(owner))) line.append("  — ").append(owner);
                        if (due != null && !"null".equals(String.valueOf(due))) line.append("  (").append(due).append(")");
                        ListItem li = new ListItem(line.toString(), bodyFont);
                        list.add(li);
                    }
                    doc.add(list);
                }
                var topics = parseStrings(insight.getKeyTopicsJson());
                if (!topics.isEmpty()) {
                    addPdfHeading(doc, "Key topics", headingFont);
                    doc.add(new Paragraph(String.join("  ·  ", topics), bodyFont));
                }
                var decisions = parseStrings(insight.getDecisionsJson());
                if (!decisions.isEmpty()) {
                    addPdfHeading(doc, "Decisions", headingFont);
                    List dlist = new List(false, false, 12);
                    for (String d : decisions) dlist.add(new ListItem(d, bodyFont));
                    doc.add(dlist);
                }
            }

            addPdfHeading(doc, "Transcript", headingFont);
            if (transcript != null) {
                var segments = parseSegments(transcript.getSegmentsJson());
                if (segments.isEmpty() && notBlank(transcript.getFullText())) {
                    doc.add(new Paragraph(transcript.getFullText(), bodyFont));
                } else {
                    for (var seg : segments) {
                        double start = toDouble(seg.get("start"));
                        double end = toDouble(seg.get("end"));
                        String text = String.valueOf(seg.getOrDefault("text", "")).trim();
                        Paragraph p = new Paragraph();
                        p.add(new Chunk(formatTime(start) + " → " + formatTime(end) + "    ", monoFont));
                        p.add(new Chunk(text, bodyFont));
                        p.setSpacingAfter(4f);
                        doc.add(p);
                    }
                }
            }

            doc.close();
            return out.toByteArray();
        }
    }

    private void addPdfHeading(com.lowagie.text.Document doc, String text, Font headingFont) throws DocumentException {
        Paragraph p = new Paragraph(text, headingFont);
        p.setSpacingBefore(12f);
        p.setSpacingAfter(6f);
        doc.add(p);
    }

    // ==================== shared helpers ====================

    private String displayTitle(Recording recording, Insight insight) {
        if (insight != null && notBlank(insight.getSmartTitle())) return insight.getSmartTitle();
        if (notBlank(recording.getOriginalFilename())) return recording.getOriginalFilename();
        return "Untitled recording";
    }

    public String safeFilename(String base, String extension) {
        String slug = base == null ? "transcript" : base.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (slug.length() > 80) slug = slug.substring(0, 80);
        slug = slug.replaceAll("^[._-]+", "").replaceAll("[._-]+$", "");
        if (slug.isEmpty()) slug = "transcript";
        return slug + "." + extension;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static double toDouble(Object o) { return o instanceof Number n ? n.doubleValue() : 0d; }

    private static String formatTime(double seconds) {
        int total = (int) Math.floor(seconds);
        int h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    private java.util.List<Map<String, Object>> parseSegments(String json) {
        return parseList(json, new TypeReference<java.util.List<Map<String, Object>>>() {});
    }

    private java.util.List<Map<String, Object>> parseActionItems(String json) {
        return parseList(json, new TypeReference<java.util.List<Map<String, Object>>>() {});
    }

    private java.util.List<String> parseStrings(String json) {
        return parseList(json, new TypeReference<java.util.List<String>>() {});
    }

    private <T> java.util.List<T> parseList(String json, TypeReference<java.util.List<T>> ref) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try { return objectMapper.readValue(json, ref); }
        catch (Exception e) { return java.util.List.of(); }
    }
}
