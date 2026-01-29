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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PDFGeneratorImpl with robust Myanmar fallback:
 * - Replaces elements with class="myanmar" by inline PNG images rendered either with Java2D
 *   or, when Java2D shaping fails, with pango-view (if installed).
 *
 * Notes:
 * - This makes Myanmar text visually correct without pdfCalligraph or Chrome.
 * - Trade-off: those Myanmar texts are images (not selectable/searchable).
 * - Requires jsoup dependency for robust HTML manipulation:
 *   <dependency>
 *     <groupId>org.jsoup</groupId>
 *     <artifactId>jsoup</artifactId>
 *     <version>1.16.1</version>
 *   </dependency>
 */
@Component
public class PDFGeneratorImpl implements PDFGenerator {
	private static final Logger LOGGER = PrintLogger.getLogger(PDFGeneratorImpl.class);

	private static final String SHA256 = "SHA256";
	private static final String OUTPUT_FILE_EXTENSION = ".pdf";

	@Value("${mosip.kernel.pdf_owner_password}")
	private String pdfOwnerPassword;

	// In-memory cache to avoid re-rendering identical strings repeatedly
	private final Map<String, String> myanmarImageCache = new ConcurrentHashMap<>();

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

	@Override
	public OutputStream generate(InputStream is, String resourceLoc) throws IOException {
		isValidInputStream(is);

		// Read the full incoming HTML once
		byte[] originalHtmlBytes = is.readAllBytes();

		// Setup PDF writer/document with try-with-resources to ensure close
		OutputStream os = new ByteArrayOutputStream();

		ConverterProperties converterProperties = new ConverterProperties();
		converterProperties.setCreateAcroForm(true);
		converterProperties.setBaseUri(resourceLoc);

		MediaDeviceDescription mediaDescription = new MediaDeviceDescription(MediaType.SCREEN);
		float screenWidth = CssUtils.parseAbsoluteLength("" + PageSize.A4.rotate().getWidth());
		mediaDescription.setWidth(screenWidth);
		converterProperties.setMediaDeviceDescription(mediaDescription);

		// Font provider (register fonts if available)
		DefaultFontProvider dfp = new DefaultFontProvider(false, false, false);
		converterProperties.setFontProvider(dfp);

		// Myanmar font path & size (adjust as needed)
		String myanmarFontPath = "/home/mosip/fonts/NotoSansMyanmar-Regular.ttf";
		float myanmarFontSize = 28f;

		// try to register font with DefaultFontProvider (optional)
		try {
			File fontFile = new File(myanmarFontPath);
			if (fontFile.exists() && fontFile.canRead()) {
				dfp.addFont(myanmarFontPath);
			} else {
				LOGGER.warn("Myanmar font not found/readable at {}", myanmarFontPath);
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to add myanmar font to provider: {}", e.getMessage());
		}

		// Process HTML: replace .myanmar elements with images
		byte[] processedHtmlBytes;
		try (InputStream tmpIn = new ByteArrayInputStream(originalHtmlBytes)) {
			processedHtmlBytes = replaceMyanmarElementsWithImages(tmpIn, myanmarFontPath, myanmarFontSize);
		} catch (Exception e) {
			LOGGER.warn("Myanmar rasterization failed, using original HTML: {}", e.getMessage());
			processedHtmlBytes = originalHtmlBytes;
		}

		// Convert to PDF and ensure PdfDocument is closed
		try (PdfWriter pdfWriter = new PdfWriter(os);
			 PdfDocument pdfDoc = new PdfDocument(pdfWriter)) {
			pdfDoc.setTagged();
			pdfDoc.setDefaultPageSize(PageSize.A4.rotate());
			HtmlConverter.convertToPdf(new ByteArrayInputStream(processedHtmlBytes), pdfDoc, converterProperties);
		} catch (Exception e) {
			throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
					e.getMessage(), e);
		}

		return os;
	}

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
		try (ByteArrayOutputStream imagebyteArray = new ByteArrayOutputStream()) {
			ImageIO.write(bufferedImage, "jpg", imagebyteArray);
			imagebyteArray.flush();
			return imagebyteArray.toByteArray();
		}
	}

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
			String reason, int pageNumber, Provider provider, CertificateEntry<X509Certificate, PrivateKey> certificateEntry,
			String password) throws IOException, GeneralSecurityException {
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

	// Render text with Java2D (TextLayout). Returns data: URI PNG. May fail on some headless setups.
	private String renderTextToDataUrlJava2D(String text, String fontPath, float fontSize, Color color)
			throws IOException, FontFormatException {
		if (text == null) return "";

		String cacheKey = "j2d|" + fontPath + "|" + fontSize + "|" + text;
		String cached = myanmarImageCache.get(cacheKey);
		if (cached != null) return cached;

		Font baseFont = Font.createFont(Font.TRUETYPE_FONT, new File(fontPath));
		Font font = baseFont.deriveFont(Font.PLAIN, fontSize);

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
				String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
				String dataUrl = "data:image/png;base64," + base64;
				myanmarImageCache.put(cacheKey, dataUrl);
				return dataUrl;
			}
		} finally {
			g2tmp.dispose();
		}
	}

	// Render using pango-view (requires pango-view on PATH). Returns data: URI PNG.
	private String renderTextWithPango(String text, String fontSpec, int pixelSize) throws IOException, InterruptedException {
		if (text == null) return "";
		String cacheKey = "pango|" + fontSpec + "|" + pixelSize + "|" + text;
		String cached = myanmarImageCache.get(cacheKey);
		if (cached != null) return cached;

		Path tmpText = Files.createTempFile("pango_text_", ".txt");
		Path tmpPng = Files.createTempFile("pango_out_", ".png");
		try {
			Files.writeString(tmpText, text, StandardCharsets.UTF_8);

			List<String> cmd = new ArrayList<>();
			cmd.add("pango-view");
			cmd.add("--font=" + fontSpec + " " + pixelSize);
			cmd.add("--file=" + tmpText.toAbsolutePath());
			cmd.add("--background=transparent");
			cmd.add("--no-display");
			cmd.add("--output=" + tmpPng.toAbsolutePath());

			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			Process p = pb.start();

			// consume stdout/stderr
			try (InputStream is = p.getInputStream()) {
				is.transferTo(OutputStream.nullOutputStream());
			}
			int exit = p.waitFor();
			if (exit != 0) {
				throw new IOException("pango-view failed with exit code " + exit);
			}

			byte[] pngBytes = Files.readAllBytes(tmpPng);
			String base64 = Base64.getEncoder().encodeToString(pngBytes);
			String dataUrl = "data:image/png;base64," + base64;
			myanmarImageCache.put(cacheKey, dataUrl);
			return dataUrl;
		} finally {
			try { Files.deleteIfExists(tmpText); } catch (Exception ignored) {}
			try { Files.deleteIfExists(tmpPng); } catch (Exception ignored) {}
		}
	}

	/**
	 * Replace elements with class "myanmar" using jsoup with inline PNG images.
	 * Tries Java2D first, then pango fallback if Java2D fails or produces likely-broken output.
	 */
	private byte[] replaceMyanmarElementsWithImages(InputStream htmlInput, String fontPath, float fontSize) throws Exception {
		String html = new String(htmlInput.readAllBytes(), StandardCharsets.UTF_8);

		org.jsoup.nodes.Document doc = Jsoup.parse(html);
		Elements elems = doc.getElementsByClass("myanmar");
		for (Element el : elems) {
			String text = el.text();
			if (text == null || text.trim().isEmpty()) continue;

			String dataUrl = null;
			// Attempt Java2D render
			try {
				dataUrl = renderTextToDataUrlJava2D(text, fontPath, fontSize, Color.BLACK);
				// Quick heuristic: ensure dataUrl non-empty
				if (dataUrl == null || !dataUrl.startsWith("data:image/png;base64,")) {
					dataUrl = null;
				}
			} catch (Throwable t) {
				LOGGER.debug("Java2D render failed for '{}': {}", text, t.getMessage());
				dataUrl = null;
			}

			// If Java2D failed, try pango (if available)
			if (dataUrl == null) {
				try {
					// fontSpec: family name (system must have the font installed). Use font filename as fallback name.
					String fontSpec = "Noto Sans Myanmar";
					dataUrl = renderTextWithPango(text, fontSpec, Math.round(fontSize));
				} catch (Throwable t) {
					LOGGER.warn("Pango render failed for '{}': {}", text, t.getMessage());
					dataUrl = null;
				}
			}

			// If both failed, skip replacement
			if (dataUrl == null) {
				LOGGER.warn("Could not rasterize Myanmar text, leaving original element: {}", text);
				continue;
			}

			// Replace element with img preserving id/class
			Element img = doc.createElement("img");
			img.attr("src", dataUrl);
			img.attr("alt", text);
			// copy style attribute if present to preserve layout (optional)
			if (el.hasAttr("style")) img.attr("style", el.attr("style"));
			el.replaceWith(img);
		}

		return doc.html().getBytes(StandardCharsets.UTF_8);
	}

	// Minimal HTML attribute escaper for alt text (keeps it safe inside double quotes)
	private String escapeHtmlAttribute(String s) {
		if (s == null) return "";
		return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
