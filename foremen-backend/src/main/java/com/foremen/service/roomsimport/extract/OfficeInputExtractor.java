package com.foremen.service.roomsimport.extract;

import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DOCX/DOC/XLSX/XLS text extraction with Apache POI (read-only; macros are never executed).
 * Tables are flattened row by row; sheets are rendered as {@code Sheet: name} plus tab-separated
 * rows using cached formula values.
 */
public class OfficeInputExtractor implements InputExtractor {

    @Override
    public boolean supports(InputKind kind) {
        return kind == InputKind.DOCX || kind == InputKind.DOC
                || kind == InputKind.XLSX || kind == InputKind.XLS;
    }

    @Override
    public ExtractedInput extract(String fileName, byte[] c, DetectedType type) throws Exception {
        String text = switch (type.kind()) {
            case DOCX -> docx(c);
            case DOC -> doc(c);
            default -> workbook(c);
        };
        return ExtractedInput.ofText(fileName, type.kind(), text);
    }

    private static String docx(byte[] c) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(c))) {
            StringBuilder sb = new StringBuilder();
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    sb.append(p.getText()).append('\n');
                } else if (el instanceof XWPFTable t) {
                    for (XWPFTableRow row : t.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText().replace('\n', ' ').trim());
                        }
                        sb.append(String.join("\t", cells)).append('\n');
                    }
                }
            }
            return sb.toString();
        }
    }

    private static String doc(byte[] c) throws Exception {
        try (WordExtractor ex = new WordExtractor(new ByteArrayInputStream(c))) {
            return ex.getText();
        }
    }

    /** Cell text; formula cells use the cached result and are never re-evaluated. */
    private static String cellText(DataFormatter fmt, Cell cell) {
        if (cell.getCellType() != CellType.FORMULA) {
            return fmt.formatCellValue(cell);
        }
        return switch (cell.getCachedFormulaResultType()) {
            case NUMERIC -> fmt.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
            case STRING -> cell.getRichStringCellValue().getString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private static String workbook(byte[] c) throws Exception {
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(c))) {
            StringBuilder sb = new StringBuilder();
            for (Sheet sheet : wb) {
                sb.append("Sheet: ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    int last = row.getLastCellNum();
                    List<String> cells = new ArrayList<>();
                    for (int i = 0; i < last; i++) {
                        Cell cell = row.getCell(i);
                        cells.add(cell == null ? "" : cellText(fmt, cell));
                    }
                    sb.append(String.join("\t", cells)).append('\n');
                }
            }
            return sb.toString();
        }
    }
}
