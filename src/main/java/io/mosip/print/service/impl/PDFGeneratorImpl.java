package io.mosip.print.service.impl;

import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.html2pdf.css.media.MediaDeviceDescription;
import com.itextpdf.html2pdf.css.media.MediaType;
import com.itextpdf.html2pdf.css.util.CssUtils;
import com.itextpdf.html2pdf.resolver.font.DefaultFontProvider;
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
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The PdfGeneratorImpl is the class you will use most when converting processed
 * Template to PDF. It contains a series of methods that accept processed
 * Template as a {@link String}, {@link File}, or {@link InputStream}, and
 * convert it to PDF in the form of an {@link OutputStream}, {@link File}
 *
 * This version includes a fallback that rasterizes Myanmar text spans (class="myanmar")
 * into inline PNG images before converting HTML → PDF, which avoids needing pdfCalligraph
 * or headless Chrome on servers where those are not available.
 *
 * Trade-off: Myanmar text becomes images (not selectable/searchable) but shapes correctly.
 *
 */
@Component
public class PDFGeneratorImpl implements PDFGenerator {
	private static final Logger LOGGER = PrintLogger.getLogger(PDFGeneratorImpl.class);

	private static final String SHA256 = "SHA256";

	private static final String OUTPUT_FILE_EXTENSION = ".pdf";

	@Value("${mosip.kernel.pdf_owner_password}")
	private String pdfOwnerPassword;

	// Simple cache to avoid re-rendering identical Burmese strings repeatedly during a single JVM run
	private final Map<String, String> myanmarImageCache = new ConcurrentHashMap<>();

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
			HtmlConverter.convertToPdf(is, os);
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
			HtmlConverter.convertToPdf(template, os);
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
			HtmlConverter.convertToPdf(new File(templatePath), outputFile);
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
		ConverterProperties converterProperties = new ConverterProperties();
		pdfDoc.setTagged();
		PageSize pageSize = PageSize.A4.rotate();
		pdfDoc.setDefaultPageSize(pageSize);
		float screenWidth = CssUtils.parseAbsoluteLength("" + pageSize.getWidth());
		MediaDeviceDescription mediaDescription = new MediaDeviceDescription(MediaType.SCREEN);
		mediaDescription.setWidth(screenWidth);

		// DefaultFontProvider - keep this to allow embedding fonts for non-rasterized content
		DefaultFontProvider dfp = new DefaultFontProvider(false, false, false);
		converterProperties.setMediaDeviceDescription(mediaDescription);
		converterProperties.setFontProvider(dfp);
		converterProperties.setBaseUri(resourceLoc);
		converterProperties.setCreateAcroForm(true);

		// Settings for Myanmar rasterization
		String myanmarFontPath = "/home/mosip/fonts/NotoSansMyanmar-Regular.ttf"; // ensure this exists
		float myanmarFontSize = 28f; // tune this to visually match your CSS

		try {
			// Read the full incoming HTML once (the InputStream may be a Velocity-processed HTML)
			byte[] originalHtmlBytes = is.readAllBytes();

			// Register the font in the font provider if available (so other text can use it)
			try {
				File fontFile = new File(myanmarFontPath);
				if (fontFile.exists() && fontFile.canRead()) {
					try {
						dfp.addFont(myanmarFontPath);
					} catch (Exception e) {
						LOGGER.warn("Could not add font to DefaultFontProvider: {}", e.getMessage());
					}
				} else {
					LOGGER.warn("Myanmar font file not found or unreadable: {}", myanmarFontPath);
				}
			} catch (Exception e) {
				LOGGER.warn("Error while registering Myanmar font: {}", e.getMessage());
			}

			// Try to rasterize Myanmar spans. Work on a fresh InputStream copy.
			InputStream processedHtmlInput;
			try (InputStream htmlStreamForReplace = new ByteArrayInputStream(originalHtmlBytes)) {
				processedHtmlInput = replaceMyanmarSpansWithImages(htmlStreamForReplace, myanmarFontPath, myanmarFontSize);
			} catch (Exception e) {
				LOGGER.warn("Myanmar rasterization failed, falling back to original HTML: {}", e.getMessage());
				processedHtmlInput = new ByteArrayInputStream(originalHtmlBytes);
			}

			HtmlConverter.convertToPdf(processedHtmlInput, pdfDoc, converterProperties);
		} catch (Exception e) {
			throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
					e.getMessage(), e);
		}
		return os;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see io.mosip.kernel.core.pdfgenerator.spi.PDFGenerator#asPDF(java.util.List)
	 */
	@Override
	public byte[] asPDF(List<BufferedImage> bufferedImages) throws IOException {
		byte[] scannedPdfFile = null;

		try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {

			PdfWriter pdfWriter = new PdfWriter(byteArrayOutputStream);
			Document document = new Document(new PdfDocument(pdfWriter));

			for (BufferedImage bufferedImage : bufferedImages) {
				Image image = new Image(ImageDataFactory.create(getImageBytesFromBufferedImage(bufferedImage)));
				image.scaleToFit(600, 750);
				document.add(image);
			}

			document.close();
			pdfWriter.close();
			scannedPdfFile = byteArrayOutputStream.toByteArray();
		} catch (IOException e) {
			throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
					e.getMessage());
		}
		return scannedPdfFile;
	}

	private byte[] getImageBytesFromBufferedImage(BufferedImage bufferedImage) throws IOException {
		byte[] imageInByte;

		ByteArrayOutputStream imagebyteArray = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "jpg", imagebyteArray);
		imagebyteArray.flush();
		imageInByte = imagebyteArray.toByteArray();
		imagebyteArray.close();

		return imageInByte;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * io.mosip.kernel.core.pdfgenerator.spi.PDFGenerator#mergePDF(java.util.List)
	 */
	@Override
	public byte[] mergePDF(List<URL> pdfFiles) throws IOException {
		try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
			com.itextpdf.text.Document document = new com.itextpdf.text.Document();
			PdfCopy pdfCopy = new PdfCopy(document, byteArrayOutputStream);
			document.open();
			for (URL file : pdfFiles) {
				PdfReader reader = new PdfReader(file);
				pdfCopy.addDocument(reader);
				pdfCopy.freeReader(reader);
				reader.close();
			}
			document.close();
			return byteArrayOutputStream.toByteArray();
		} catch (IOException | DocumentException e) {
			throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
					e.getMessage());
		}
	}

	@Override
	public OutputStream signAndEncryptPDF(byte[] pdf, io.mosip.print.model.Rectangle rectangle,
										  String reason, int pageNumber, Provider provider,
										  CertificateEntry<X509Certificate, PrivateKey> certificateEntry, String password)
			throws IOException, GeneralSecurityException {
		OutputStream outputStream = new ByteArrayOutputStream();
		PdfReader pdfReader = null;
		PdfStamper pdfStamper = null;
		try {
			pdfReader = new PdfReader(pdf);
			pdfStamper = PdfStamper.createSignature(pdfReader, outputStream, '\0');

			if (password != null && !password.trim().isEmpty()) {
				pdfStamper.setEncryption(password.getBytes(), pdfOwnerPassword.getBytes(),
						com.itextpdf.text.pdf.PdfWriter.ALLOW_PRINTING,
						com.itextpdf.text.pdf.PdfWriter.ENCRYPTION_AES_256);
			}
			PdfSignatureAppearance signAppearance = pdfStamper.getSignatureAppearance();

			signAppearance.setReason(reason);
			// comment next line to have an invisible signature
			signAppearance.setVisibleSignature(
					new Rectangle(rectangle.getLlx(), rectangle.getLly(), rectangle.getUrx(), rectangle.getUry()),
					pageNumber, null);

			OcspClient ocspClient = new OcspClientBouncyCastle(null);
			TSAClient tsaClient = null;
			for (X509Certificate certificate : certificateEntry.getChain()) {
				String tsaUrl = CertificateUtil.getTSAURL(certificate);
				if (tsaUrl != null) {
					tsaClient = new TSAClientBouncyCastle(tsaUrl);
					break;
				}
				signAppearance.setCertificate(certificate);
			}

			List<CrlClient> crlList = new ArrayList<>();
			crlList.add(new CrlClientOnline(certificateEntry.getChain()));

			ExternalSignature pks = new PrivateKeySignature(certificateEntry.getPrivateKey(), "SHA256",
					provider.getName());
			ExternalDigest digest = new BouncyCastleDigest();

			// Sign the document using the detached mode, CMS or CAdES equivalent.
			MakeSignature.signDetached(signAppearance, digest, pks, certificateEntry.getChain(), crlList, ocspClient,
					tsaClient, 0, CryptoStandard.CMS);

			pdfStamper.close();

		} catch (DocumentException e) {
			throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
					e.getMessage(), e);
		} finally {
			outputStream.close();
			if (pdfStamper != null) {
				closeQuietly(pdfStamper);
			}
			if (pdfReader != null) {
				pdfReader.close();
			}
		}
		return outputStream;
	}

	/*

	 */

	// Quietly close the pdfStamper.
	private void closeQuietly(final PdfStamper pdfStamper) throws IOException {
		try {
			pdfStamper.close();
		} catch (DocumentException e) {
			throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
					e.getMessage(), e);
		}
	}

	private void isValidInputStream(InputStream dataInputStream) {
		if (EmptyCheckUtils.isNullEmpty(dataInputStream)) {
			throw new PDFGeneratorException(
					PDFGeneratorExceptionCodeConstant.INPUTSTREAM_NULL_EMPTY_EXCEPTION.getErrorCode(),
					PDFGeneratorExceptionCodeConstant.INPUTSTREAM_NULL_EMPTY_EXCEPTION.getErrorMessage());
		}
	}

	// Render Myanmar text to a PNG data: URI. Uses a basic in-memory cache.
	private String renderTextToDataUrl(String text, String fontPath, float fontSize, Color color) throws IOException, FontFormatException {
		if (text == null) return "";

		String cacheKey = text + "|" + fontSize;
		String cached = myanmarImageCache.get(cacheKey);
		if (cached != null) return cached;

		// Load the TTF font from file
		Font baseFont = Font.createFont(Font.TRUETYPE_FONT, new File(fontPath));
		Font font = baseFont.deriveFont(Font.PLAIN, fontSize);

		// compute bounds using TextLayout (better for complex scripts)
		BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2tmp = tmp.createGraphics();
		try {
			g2tmp.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			FontRenderContext frc = g2tmp.getFontRenderContext();
			TextLayout tl = new TextLayout(text, font, frc);
			Rectangle2D bounds = tl.getBounds();

			int padding = 6;
			int width = Math.max(1, (int) Math.ceil(bounds.getWidth()) + padding * 2);
			int height = Math.max(1, (int) Math.ceil(bounds.getHeight()) + padding * 2);

			BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			try {
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(new Color(255, 255, 255, 0));
				g.fillRect(0, 0, width, height);
				g.setFont(font);
				g.setColor(color);

				float x = padding - (float) bounds.getX();
				float y = padding - (float) bounds.getY();
				tl.draw(g, x, y);
			} finally {
				g.dispose();
			}

			try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
				ImageIO.write(img, "png", baos);
				byte[] pngBytes = baos.toByteArray();
				String base64 = Base64.getEncoder().encodeToString(pngBytes);
				String dataUrl = "data:image/png;base64," + base64;
				myanmarImageCache.put(cacheKey, dataUrl);
				return dataUrl;
			}
		} finally {
			g2tmp.dispose();
		}
	}

	/**
	 * Replace <span class="myanmar">...</span> with inline PNG data URLs.
	 * IMPORTANT: run this after template variables have been replaced (i.e., after Velocity).
	 */
	private InputStream replaceMyanmarSpansWithImages(InputStream htmlInput, String fontPath, float fontSize) throws Exception {
		// Read HTML fully
		String html = new String(htmlInput.readAllBytes(), StandardCharsets.UTF_8);

		// Pattern to match <span ... class="... myanmar ...">...</span>
		Pattern p = Pattern.compile("(?i)<span\\b[^>]*\\bclass\\s*=\\s*['\"][^'\"]*\\bmyanmar\\b[^'\"]*['\"][^>]*>(.*?)</span>", Pattern.DOTALL);
		Matcher m = p.matcher(html);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String inner = m.group(1).trim();
			if (inner.isEmpty()) {
				m.appendReplacement(sb, m.group(0));
				continue;
			}

			// Remove inner tags if any (simple cleanup). For complex HTML, consider jsoup approach.
			String plainText = inner.replaceAll("<[^>]+>", "").trim();
			if (plainText.isEmpty()) {
				m.appendReplacement(sb, m.group(0));
				continue;
			}

			String dataUrl;
			try {
				dataUrl = renderTextToDataUrl(plainText, fontPath, fontSize, Color.BLACK);
			} catch (Exception ex) {
				LOGGER.warn("Failed to rasterize myanmar text '{}': {}", plainText, ex.getMessage());
				m.appendReplacement(sb, m.group(0));
				continue;
			}

			// Build replacement img tag. Use alt attribute to keep accessibility and for fallback.
			String imgTag = "<img src=\"" + dataUrl + "\" style=\"vertical-align:middle; display:inline-block;\" alt=\"" + escapeHtmlAttribute(plainText) + "\"/>";
			imgTag = Matcher.quoteReplacement(imgTag);
			m.appendReplacement(sb, imgTag);
		}
		m.appendTail(sb);

		return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	// Minimal HTML attribute escaper for alt text (keeps it safe inside double quotes)
	private String escapeHtmlAttribute(String s) {
		if (s == null) return "";
		return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
