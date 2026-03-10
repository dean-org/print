package io.mosip.print.service.impl;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.mosip.print.constant.QrVersion;
import io.mosip.print.constant.QrcodeConstants;
import io.mosip.print.constant.QrcodeExceptionConstants;
import io.mosip.print.exception.QrcodeGenerationException;
import io.mosip.print.spi.QrCodeGenerator;
import io.mosip.print.util.QrcodegeneratorUtils;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

/**
 * Class which provides functionality to generate QR Code
 * 
 * @author Urvil Joshi
 *
 * @since 1.0.0
 */
@Component
public class QrcodeGeneratorImpl implements QrCodeGenerator<QrVersion> {

	/**
	 * {@link QRCodeWriter} instance
	 */
	private static QRCodeWriter qrCodeWriter;
	/**
	 * Configurations for QrCode Generator
	 */
	private static Map<EncodeHintType, Object> configMap;
	private static final Float OVERLAY_TO_QRCODE_RATIO  = 1f;

	static {
		qrCodeWriter = new QRCodeWriter();
		configMap = new EnumMap<>(EncodeHintType.class);
		configMap.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
		configMap.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.kernel.qrcode.generator.zxing.QrCode#generateQrCode(java.lang.
	 * String, io.mosip.kernel.qrcode.generator.zxing.constant.QrVersion)
	 */
	@Override
	public byte[] generateQrCode(String data, QrVersion version) throws QrcodeGenerationException, IOException {
		QrcodegeneratorUtils.verifyInput(data, version);
		configMap.put(EncodeHintType.QR_VERSION, version.getVersion());
		BitMatrix byteMatrix = null;
		try {
			byteMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, version.getSize(), version.getSize(),
					configMap);
		} catch (WriterException | IllegalArgumentException exception) {
			throw new QrcodeGenerationException(QrcodeExceptionConstants.QRCODE_GENERATION_EXCEPTION.getErrorCode(),
					QrcodeExceptionConstants.QRCODE_GENERATION_EXCEPTION.getErrorMessage() + exception.getMessage(),
					exception);
		}
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		MatrixToImageWriter.writeToStream(byteMatrix, QrcodeConstants.FILE_FORMAT, outputStream);
		return outputStream.toByteArray();

	}
	@Override
	public byte[] generateQrCodeWithLogo(String data, QrVersion version, BufferedImage logoImage) throws QrcodeGenerationException, IOException {
		QrcodegeneratorUtils.verifyInput(data, version);
		configMap.put(EncodeHintType.QR_VERSION, version.getVersion());
		BitMatrix byteMatrix = null;
		
		int maxLogoSize = version.getSize() * 30 / 100;
		// Resize logo if it is too big
		if (logoImage != null && (logoImage.getWidth() > maxLogoSize || logoImage.getHeight() > maxLogoSize)) {
			logoImage = resizeImage(logoImage, maxLogoSize, maxLogoSize);
		}
		
		try {
			byteMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, version.getSize(), version.getSize(),
					configMap);
		} catch (WriterException | IllegalArgumentException exception) {
			throw new QrcodeGenerationException(QrcodeExceptionConstants.QRCODE_GENERATION_EXCEPTION.getErrorCode(),
					QrcodeExceptionConstants.QRCODE_GENERATION_EXCEPTION.getErrorMessage() + exception.getMessage(),
					exception);
		}
		BufferedImage combinedImage = getCombinedImage(byteMatrix, logoImage);
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		if (!ImageIO.write(combinedImage, QrcodeConstants.FILE_FORMAT, outputStream)) {
			throw new QrcodeGenerationException("Could not write the qrcode of format " + QrcodeConstants.FILE_FORMAT);
		}
		return outputStream.toByteArray();
	}

	private BufferedImage getCombinedImage(BitMatrix bitMatrix, BufferedImage logoImage) {

		BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);

		// Calculate the delta height and width between QR code and logo
		int deltaHeight = qrImage.getHeight() - logoImage.getHeight();
		int deltaWidth = qrImage.getWidth() - logoImage.getWidth();

		// Initialize combined image
		BufferedImage combined = new BufferedImage(qrImage.getHeight(), qrImage.getWidth(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = (Graphics2D) combined.getGraphics();

		// Draw the QR code image
		g.drawImage(qrImage, 0, 0, null);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, OVERLAY_TO_QRCODE_RATIO));//logo middle
		//g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DEFAULT_OVERLAY_TRANSPARENCY));//logo bg
		// Draw the logo image
		g.drawImage(logoImage, Math.round(deltaWidth / 2), Math.round(deltaHeight / 2), null);
		g.dispose();
		return combined;
	}

	@Override
	public byte[] generateQrCodeFromBinaryData(String data, QrVersion version)
			throws QrcodeGenerationException, IOException {
		QrcodegeneratorUtils.verifyInput(data, version);
		StringBuilder stringBuilder = new StringBuilder();
		Arrays.stream(data.split("(?<=\\G.{8})")).forEach(s -> stringBuilder.append((char) Integer.parseInt(s, 2))); 
		return generateQrCode(stringBuilder.toString(), version);
	}

	public static BufferedImage resizeImage(BufferedImage originalImage, int maxWidth, int maxHeight) {
		int originalWidth = originalImage.getWidth();
		int originalHeight = originalImage.getHeight();

		// Calculate the scaling factor while preserving aspect ratio
		double scale = Math.min((double) maxWidth / originalWidth, (double) maxHeight / originalHeight);

		int newWidth = (int) (originalWidth * scale);
		int newHeight = (int) (originalHeight * scale);

		// Create a new buffered image with the new dimensions
		BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);

		Graphics2D g2d = resizedImage.createGraphics();
		g2d.drawImage(originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null);
		g2d.dispose();

		return resizedImage;
	}
}
