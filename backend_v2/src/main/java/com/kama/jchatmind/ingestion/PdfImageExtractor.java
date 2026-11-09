package com.kama.jchatmind.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class PdfImageExtractor {

    public List<PdfEmbeddedImage> extract(InputStream inputStream) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream)) {
            List<PdfEmbeddedImage> images = new ArrayList<>();
            ImageCollector collector = new ImageCollector(images);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDPage pdPage = document.getPage(page - 1);
                collector.setPageNumber(page);
                collector.processPage(pdPage);
            }
            return images;
        }
    }

    public record PdfEmbeddedImage(
            int pageNumber,
            float[] bbox,
            byte[] pngBytes
    ) {
    }

    private static class ImageCollector extends PDFStreamEngine {

        private final List<PdfEmbeddedImage> images;
        private int pageNumber;

        private ImageCollector(List<PdfEmbeddedImage> images) {
            this.images = images;
            addOperator(new Concatenate());
            addOperator(new DrawObject());
            addOperator(new SetGraphicsStateParameters());
            addOperator(new Save());
            addOperator(new Restore());
            addOperator(new SetMatrix());
        }

        private void setPageNumber(int pageNumber) {
            this.pageNumber = pageNumber;
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName())) {
                PDXObject xobject = getResources().getXObject((COSName) operands.get(0));
                if (xobject instanceof PDImageXObject image) {
                    collectImage(image);
                } else if (xobject instanceof PDFormXObject form) {
                    showForm(form);
                }
            }
            super.processOperator(operator, operands);
        }

        private void collectImage(PDImageXObject image) throws IOException {
            BufferedImage renderedImage = image.getImage();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(renderedImage, "png", output)) {
                log.warn("PDF 图片无法编码为 PNG，跳过: page={}", pageNumber);
                return;
            }
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            images.add(new PdfEmbeddedImage(
                    pageNumber,
                    new float[]{
                            ctm.getTranslateX(),
                            ctm.getTranslateY(),
                            ctm.getScalingFactorX(),
                            ctm.getScalingFactorY()
                    },
                    output.toByteArray()
            ));
        }
    }
}