package io.mosip.print.service.impl;

import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.html2pdf.css.media.MediaDeviceDescription;
import com.itextpdf.html2pdf.css.media.MediaType;
import com.itextpdf.html2pdf.css.util.CssUtils;
import com.itextpdf.html2pdf.resolver.font.DefaultFontProvider;
import com.itextpdf.io.font.FontConstants;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PdfCopy;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfSignatureAppearance;
import com.itextpdf.text.pdf.PdfStamper;
import com.itextpdf.text.pdf.security.*;
import com.itextpdf.text.pdf.security.MakeSignature.CryptoStandard;
import io.mosip.print.constant.PDFGeneratorExceptionCodeConstant;
import io.mosip.print.exception.PDFGeneratorException;
import io.mosip.print.logger.PrintLogger;
import io.mosip.print.model.CertificateEntry;
import io.mosip.print.spi.PDFGenerator;
import io.mosip.print.util.EmptyCheckUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * The PdfGeneratorImpl is the class you will use most when converting processed
 * Template to PDF. It contains a series of methods that accept processed
 * Template as a {@link String}, {@link File}, or {@link InputStream}, and
 * convert it to PDF in the form of an {@link OutputStream}, {@link File}
 * 
 * Burmese font support added for proper text rendering.
 * 
 * @author Urvil Joshi
 * @since 1.0.0
 *
 */
@Component
public class PDFGeneratorImpl implements PDFGenerator {
	private static final Logger LOGGER = PrintLogger.getLogger(PDFGeneratorImpl.class);
	
	private static final String SHA256 = "SHA256";

	private static final String OUTPUT_FILE_EXTENSION = ".pdf";

	@Value("${mosip.kernel.pdf_owner_password}")
	private String pdfOwnerPassword;

	// Path to Burmese Noto Sans Font
	private static final String BURMESE_FONT_PATH = "NotoSansMyanmar-Regular.ttf";

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.kernel.core.pdfgenerator.spi.PDFGenerator#generate(java.io.
	 * InputStream)
	 */
	@Override
	public OutputStream generate(InputStream is) throws IOException {
		isValidInputStream(is);
		OutputStream os = new ByteArrayOutputStream();
		try {
			HtmlConverter.convertToPdf(is, os, getConverterProperties());
		} catch (Exception e) {
			throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
				e.getMessage());
		}
		return os;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.kernel.core.pdfgenerator.spi.PDFGenerator#generate(java.lang.String)
	 */
	@Override
	public OutputStream generate(String template) throws IOException {
		OutputStream os = new ByteArrayOutputStream();
		try {
			HtmlConverter.convertToPdf(template, os, getConverterProperties());
		} catch (Exception e) {
			throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
				PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorMessage(), e);
		}
		return os;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * io.mosip.kernel.core.pdfgenerator.spi.PDFGenerator#generate(java.lang.String,
	 * java.lang.String, java.lang.String)
	 */
	@Override
	public void generate(String templatePath, String outpuFilePath, String outputFileName) throws IOException {
		File outputFile = new File(outpuFilePath + outputFileName + OUTPUT_FILE_EXTENSION);
		try {
			HtmlConverter.convertToPdf(new File(templatePath), outputFile, getConverterProperties());
		} catch (Exception e) {
			throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
				PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorMessage(), e);
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.kernel.core.pdfgenerator.spi.PDFGenerator#generate(java.io.
	 * InputStream, java.lang.String)
	 */
	@Override
	public OutputStream generate(InputStream is, String resourceLoc) throws IOException {
		isValidInputStream(is);
		OutputStream os = new ByteArrayOutputStream();
		PdfWriter pdfWriter = new PdfWriter(os);
		PdfDocument pdfDoc = new PdfDocument(pdfWriter);
		ConverterProperties converterProperties = getConverterProperties();
		pdfDoc.setTagged();
		PageSize pageSize = PageSize.A4.rotate();
		pdfDoc.setDefaultPageSize(pageSize);
		float screenWidth = CssUtils.parseAbsoluteLength("") + pageSize.getWidth();
		MediaDeviceDescription mediaDescription = new MediaDeviceDescription(MediaType.SCREEN);
		mediaDescription.setWidth(screenWidth);
		converterProperties.setMediaDeviceDescription(mediaDescription);
		try {
			HtmlConverter.convertToPdf(is, pdfDoc, converterProperties);
		} catch (Exception e) {
			throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
				e.getMessage());
		}
		return os;
	}

	// Initializes ConverterProperties with Burmese font.
	private ConverterProperties getConverterProperties() throws IOException {
		ConverterProperties converterProperties = new ConverterProperties();
		DefaultFontProvider fontProvider = new DefaultFontProvider();
		FontProgram burmeseFont = FontProgramFactory.createFont(BURMESE_FONT_PATH);
		fontProvider.addFont(burmeseFont);
		converterProperties.setFontProvider(fontProvider);
		return converterProperties;
	}

	private void isValidInputStream(InputStream dataInputStream) {
		if (EmptyCheckUtils.isNullEmpty(dataInputStream)) {
			throw new PDFGeneratorException(
				PDFGeneratorExceptionCodeConstant.INPUTSTREAM_NULL_EMPTY_EXCEPTION.getErrorCode(),
				PDFGeneratorExceptionCodeConstant.INPUTSTREAM_NULL_EMPTY_EXCEPTION.getErrorMessage());
		}
	}
}