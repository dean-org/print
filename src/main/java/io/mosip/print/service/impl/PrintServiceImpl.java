package io.mosip.print.service.impl;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.mosip.print.constant.*;
import io.mosip.print.dto.*;
import io.mosip.print.exception.*;
import io.mosip.print.logger.LogDescription;
import io.mosip.print.logger.PrintLogger;
import io.mosip.print.model.CredentialStatusEvent;
import io.mosip.print.model.EventModel;
import io.mosip.print.model.StatusEvent;
import io.mosip.print.service.PrintService;
import io.mosip.print.service.UinCardGenerator;
import io.mosip.print.spi.CbeffUtil;
import io.mosip.print.spi.QrCodeGenerator;
import io.mosip.print.util.*;
import io.mosip.vercred.CredentialsVerifier;
import io.mosip.vercred.exception.ProofDocumentNotFoundException;
import io.mosip.vercred.exception.ProofTypeNotFoundException;
import io.mosip.vercred.exception.PubicKeyNotFoundException;
import io.mosip.vercred.exception.UnknownException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Service
public class PrintServiceImpl implements PrintService {
	
    private static int passwordLengthPerAttribute=4;
    /**
     * The Constant FILE_SEPARATOR.
     */
    public static final String FILE_SEPARATOR = File.separator;
    /**
     * The Constant VID_CREATE_ID.
     */
    public static final String VID_CREATE_ID = "registration.processor.id.repo.generate";
    /**
     * The Constant REG_PROC_APPLICATION_VERSION.
     */
    public static final String REG_PROC_APPLICATION_VERSION = "registration.processor.id.repo.vidVersion";
    /**
     * The Constant DATETIME_PATTERN.
     */
    public static final String DATETIME_PATTERN = "mosip.print.datetime.pattern";
    public static final String VID_TYPE = "registration.processor.id.repo.vidType";
    /**
     * The Constant VALUE.
     */
    private static final String VALUE = "value";
    /**
     * The Constant UIN_CARD_TEMPLATE.
     */
    private static final String UIN_CARD_TEMPLATE = "RPR_UIN_CARD_TEMPLATE";
    private static final String UIN_CARD_EMAIL_SUB = "RPR_UIN_CARD_EMAIL_SUB";
    private static final String UIN_CARD_EMAIL = "RPR_UIN_CARD_EMAIL";
    private static final String ACCT_EMAIL_SUB = "RPR_ACCT_NOTIFY_EMAIL_SUB";
    private static final String ACCT_EMAIL = "RPR_ACCT_NOTIFY_EMAIL";
    private static final String BEARER = "Bearer ";
    /**
     * The Constant FACE.
     */
    private static final String FACE = "Face";
    /**
     * The Constant UIN_TEXT_FILE.
     */
    private static final String UIN_TEXT_FILE = "textFile";
    /**
     * The Constant APPLICANT_PHOTO.
     */
    private static final String APPLICANT_PHOTO = "ApplicantPhoto";
    /**
     * The Constant QRCODE.
     */
    private static final String QRCODE = "QrCode";

	/** The Constant UINCARDPASSWORD. */
	private static final String UINCARDPASSWORD = "mosip.print.service.uincard.password";

    @Autowired
    CryptoUtil cryptoUtil;
    /**
     * The print logger.
     */
    Logger printLogger = PrintLogger.getLogger(PrintServiceImpl.class);
    private final String topic = "CREDENTIAL_STATUS_UPDATE";
    @Autowired
    private WebSubSubscriptionHelper webSubSubscriptionHelper;
    @Autowired
    private DataShareUtil dataShareUtil;
    @Autowired
    private RestApiClient restApiClient;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private CryptoCoreUtil cryptoCoreUtil;
    /**
     * The core audit request builder.
     */
    @Autowired
    private AuditLogRequestBuilder auditLogRequestBuilder;
    /**
     * The template generator.
     */
    @Autowired
    private TemplateGenerator templateGenerator;
    /**
     * The utilities.
     */
    @Autowired
    private Utilities utilities;
    /**
     * The uin card generator.
     */
    @Autowired
    private UinCardGenerator<byte[]> uinCardGenerator;
    /**
     * The qr code generator.
     */
    @Autowired
    private QrCodeGenerator<QrVersion> qrCodeGenerator;
    /**
     * The cbeffutil.
     */
    @Autowired
    private CbeffUtil cbeffutil;
    /**
     * The env.
     */
    @Autowired
    private Environment env;
    @Autowired
    private CredentialsVerifier credentialsVerifier;
    @Value("${mosip.datashare.partner.id}")
    private String partnerId;
    @Value("${mosip.datashare.policy.id}")
    private String policyId;
    @Value("${mosip.template-language}")
    private String defaultTplLangCode;
    @Value("#{T(java.util.Arrays).asList('${mosip.supported-languages:}')}")
    private List<String> supportedLang;
    @Value("${mosip.print.verify.credentials.flag:true}")
    private boolean verifyCredentialsFlag;
    @Value("${token.request.clientId}")
    private String clientId;
    @Value("${mosip.send.uin.email.attachment.enabled:false}")
    private Boolean emailUINEnabled;
    @Value("${mosip.print.service.uincard.pdf.password.enable:false}")
    private boolean isPasswordProtected;
    @Value("${mosip.send.uin.default-emailIds}")
    private String defaultEmailIds;
    @Value("${mosip.idschema.attribute.email:email}")
    private String emailAttribute;
    @Value("${mosip.print.service.mpesa.enabled:false}")
    private Boolean isMpesaEnabled;
    @Value("${mosip.print.service.mpesa.account.creation.url}")
    private String mpesaAccountCreationUrl;
    @Value("${mosip.print.service.mpesa.agent.login.url}")
    private String mpesaAgentLoginUrl;
    @Value("${mosip.print.service.mpesa.account.deposit.url}")
    private String mpesaAccountDepositUrl;
    @Value("${mosip.print.service.mpesa.agent.username}")
    private String mpesaAgentUserName;
    @Value("${mosip.print.service.mpesa.agent.password}")
    private String mpesaAgentPassword;
    @Value("${mosip.print.service.mpesa.password.length}")
    private int mpesaPasswordLength;
    @Value("${mosip.print.service.mpesa.account.deposit.amount}")
    private int mpesaDepositAmount;
    @Value("${mosip.print.service.id}")
    private String serviceId;
    @Value("${mosip.print.service.mpesa.subs.username.suffix}")
    private String subsUserNameSuffix;
    @Value("${mosip.print.service.mpesa.subs.password}")
    private String subsPassword;

    @Value("${mosip.default.user-preferred-language-attribute:#{null}}")
    private String userPreferredLanguageAttribute;
    @Value("${mosip.print.qrcode.logo:#{null}}")
    private String qrCodeLogo;
    @Value("${mosip.print.qrcode.logo.enabled:true}")
    private boolean isQrCodeWithLogoEnabled;
    @Value("#{${mosip.print.language-code.map:{}}}")
    private Map<String, String> languageCodes;

    @Autowired
    private NotificationUtil notificationUtil;

	 public String preferredLang = null;
	

    public boolean generateCard(EventModel eventModel) {
        boolean isPrinted = false;
        try {
            printStatusUpdate(eventModel.getEvent().getTransactionId(), CredentialStatusConstant.RECEIVED.name(), null);
            String credential = getCredential(eventModel);
            String decodedCredential = decryptCredential(credential);
            printLogger.debug("vc is printed security valuation.... : {}", decodedCredential);
            if (!hasPrintCredentialVerified(eventModel, decodedCredential)) {
				return false;
			}
            byte[] pdfbytes = getDocuments(decodedCredential,
                    eventModel.getEvent().getData().get("credentialType").toString(), eventModel.getEvent().getData().get("protectionKey").toString(),
                    eventModel.getEvent().getTransactionId(), "UIN", isPasswordProtected, eventModel.getEvent().getId(),
                    (eventModel.getEvent().getData().get("registrationId") == null ? null : eventModel.getEvent().getData().get("registrationId").toString())).get("uinPdf");
            isPrinted = true;
        } catch (Exception e) {
            printLogger.error(e.getMessage(), e);
            isPrinted = false;
        }
        return isPrinted;
    }

    /**
     * Decrypts print credential using MOSIP's decryption logic in ref. impl.
     * Print partner system has to use their own decryption logic to decrypt the credential.
     * @param credential
     * @return
     */
    private String decryptCredential(String credential) {
        return cryptoCoreUtil.decrypt(credential);
    }

    /**
     * Verifies Print credentials using credential verifier.
     * @param eventModel
     * @param decodedCredential
     * @return
     */
    private boolean hasPrintCredentialVerified(EventModel eventModel, String decodedCredential) {

        if (verifyCredentialsFlag) {
            printLogger.info("Configured received credentials to be verified. Flag {}", verifyCredentialsFlag);
            try {
                boolean verified = credentialsVerifier.verifyPrintCredentials(decodedCredential);
                if (!verified) {
                    printLogger.error("Received Credentials failed in verifiable credential verify method. So, the credentials will not be printed." +
                            " Id: {}, Transaction Id: {}", eventModel.getEvent().getId(), eventModel.getEvent().getTransactionId());
                    return false;
                }
            } catch (ProofDocumentNotFoundException | ProofTypeNotFoundException e) {
                printLogger.error("Proof document is not available in the received credentials." +
                        " Id: {}, Transaction Id: {}", eventModel.getEvent().getId(), eventModel.getEvent().getTransactionId());
                return false;
            } catch (UnknownException | PubicKeyNotFoundException e) {
                printLogger.error("Received Credentials failed in verifiable credential verify method. So, the credentials will not be printed." +
                        " Id: {}, Transaction Id: {}", eventModel.getEvent().getId(), eventModel.getEvent().getTransactionId());
                return false;
            }
        }
        return true;
    }

    /**
     * Fetch credential from the event if not using datashare URL.
     * @param eventModel
     * @return
     * @throws Exception
     */
    private String getCredential(EventModel eventModel) throws Exception {
        String credential;
        if (eventModel.getEvent().getDataShareUri() == null || eventModel.getEvent().getDataShareUri().isEmpty()) {
            credential = eventModel.getEvent().getData().get("credential").toString();
        } else {
            String dataShareUrl = eventModel.getEvent().getDataShareUri();
            URI dataShareUri = URI.create(dataShareUrl);
            credential = restApiClient.getApi(dataShareUri, String.class);
        }
        return credential;
    }

    /*
     * (non-Javadoc)
     *
     * @see io.mosip.print.service.PrintService#
     * getDocuments(io.mosip.registration.processor.core.constant.IdType,
     * java.lang.String, java.lang.String, boolean)
     */
    private Map<String, byte[]> getDocuments(String credential, String credentialType, String encryptionPin,
                                             String requestId,
                                             String cardType,
                                             boolean isPasswordProtected, String refId, String registrationId) {
        printLogger.debug("PrintServiceImpl::getDocuments()::entry");
        String credentialSubject;
        Map<String, byte[]> byteMap = new HashMap<>();
        String uin = null, residentEmailId = null;
        LogDescription description = new LogDescription();
        String password = null;
        boolean isPhotoSet = false;
        String individualBio = null;
        Map<String, Object> attributes = new LinkedHashMap<>();
        boolean isTransactionSuccessful = false;
        String template = UIN_CARD_TEMPLATE;
        byte[] pdfBytes = null;
        String templateLang = null;
		
        try {

            credentialSubject = getCrdentialSubject(credential);
            org.json.JSONObject credentialSubjectJson = new org.json.JSONObject(credentialSubject);
            org.json.JSONObject decryptedJson = decryptAttribute(credentialSubjectJson, encryptionPin, credential);
			org.json.JSONObject demographicJson = new org.json.JSONObject(decryptedJson.toString());
            demographicJson.remove("biometrics");
            String biometricJson = decryptedJson.has("biometrics") ? decryptedJson.getString("biometrics") : null;
            // Face image
            byte[] faceImageBytes = null;
            if (biometricJson != null) {
                List<String> subtype = new ArrayList<>();
                CbeffToBiometricUtil util = new CbeffToBiometricUtil(cbeffutil);
                byte[] photoByte = util.getImageBytes(biometricJson, FACE, subtype);
                if (photoByte != null) {
                    faceImageBytes = extractFaceImageData(photoByte);
                }
            }
			printLogger.info("demographic_data:\n{}", demographicJson);
			printLogger.info("Biometrics:\n{}", biometricJson);
			printLogger.info("Face:\n{}",faceImageBytes);
            if (decryptedJson.has(emailAttribute)) {
                residentEmailId = decryptedJson.getString(emailAttribute);
            }
            if (!StringUtils.hasText(registrationId)) {
                printLogger.info(decryptedJson.get("id").toString());
                //registrationId = getRid(decryptedJson.get("id"));
            }
            if (decryptedJson.has("biometrics")) {
                individualBio = decryptedJson.getString("biometrics");
				String individualBiometric = new String(individualBio);
                isPhotoSet = setApplicantPhoto(individualBiometric, attributes);
                attributes.put("isPhotoSet", isPhotoSet);
            }
            uin = decryptedJson.getString("UIN");
			// printLogger.info("FULL CREDENTIAL:\n{}", credential);
            // printLogger.info("CREDENTIAL SUBJECT JSON:\n{}", credentialSubjectJson.toString(2));
			// printLogger.info("Decrypted Credential JSON:\n{}", decryptedJson.toString(2));
			// String uid = decryptedJson.getString("UID");

            setTemplateAttributes(decryptedJson.toString(), attributes);
			String myanmarName = (String) attributes.get("fullName_bur"); // adjust key if different
			if(myanmarName ==null || myanmarName.isEmpty()){
				myanmarName = (String) attributes.get("fullName_eng");
			}
			if (myanmarName != null && !myanmarName.isEmpty()) {
				// String fullText = "အမည်: " + myanmarName;
			    String imagePath = convertTextToImageFile(myanmarName, uin + "_name");
			    attributes.put("myanmarNameImage", "file:" + imagePath);
			}
            attributes.put(IdType.UIN.toString(), uin);
			// attributes.put(IdType.UID.toString(), uid);
            attributes.put(IdType.RID.toString(), registrationId);

            String prefLangAttr = (String) attributes.get(userPreferredLanguageAttribute);
			preferredLang = prefLangAttr;
            printLogger.info("userPreferredLanguageAttribute: {}, prefLangAttr: {}, languageCodes: {}", userPreferredLanguageAttribute, prefLangAttr, languageCodes);
            if (prefLangAttr != null && languageCodes != null && !languageCodes.isEmpty()) {
                templateLang = (String) languageCodes.get(Base64.encodeBase64String(prefLangAttr.getBytes()));
            }
            if (!StringUtils.hasText(templateLang)) {
                templateLang = defaultTplLangCode;
            }
            printLogger.info("templateLang, {}", templateLang);
            if (isPasswordProtected) {
                password = getPassword(decryptedJson, templateLang);
            }

            if (credentialType.equalsIgnoreCase("qrcode")) {
                boolean isQRcodeSet = setQrCode(decryptedJson.toString(), attributes, isPhotoSet);
                InputStream uinArtifact = templateGenerator.getTemplate(template, attributes, templateLang);
                pdfBytes = uinCardGenerator.generateUinCard(uinArtifact, UinCardType.PDF,
                        password);
            } else {

                if (!isPhotoSet) {
                    printLogger.debug(PlatformErrorMessages.PRT_PRT_APPLICANT_PHOTO_NOT_SET.name());
                }

//                byte[] textFileByte = createTextFile(decryptedJson.toString());
//                byteMap.put(UIN_TEXT_FILE, textFileByte);

                boolean isQRcodeSet = setQrCode(decryptedJson.toString(), attributes, isPhotoSet);
                if (!isQRcodeSet) {
                    printLogger.debug(PlatformErrorMessages.PRT_PRT_QRCODE_NOT_SET.name());
                }
                printLogger.info("Attributes:{}", JSONObject.toJSONString(attributes));
				printLogger.info("Template Attributes Keys: {}", attributes.keySet());
                // getting template and placing original valuespng
                InputStream uinArtifact = templateGenerator.getTemplate(template, attributes, templateLang);
                if (uinArtifact == null) {
                    printLogger.error(PlatformErrorMessages.PRT_TEM_PROCESSING_FAILURE.name());
                    throw new TemplateProcessingFailureException(
                            PlatformErrorMessages.PRT_TEM_PROCESSING_FAILURE.getCode());
                }
                pdfBytes = uinCardGenerator.generateUinCard(uinArtifact, UinCardType.PDF, password);
				File tempFile = new File("/tmp/" + uin + "_name.png");
				if (tempFile.exists()) {
					tempFile.delete();
				}
            }

            // Send UIN Card Pdf to Email
            if (emailUINEnabled) {
                sendUINInEmail(residentEmailId, registrationId, attributes, pdfBytes, templateLang);
            }
			printLogger.info("FinalAttributes:{}", JSONObject.toJSONString(attributes));
            byteMap.put("uinPdf", pdfBytes);
            // Simple MPESA integration
            if (isMpesaEnabled) {
                integrateMpesa(residentEmailId, attributes, templateLang);
            }
            String datashareUrl = getDatashareUrl(pdfBytes);
            printStatusUpdate(requestId, CredentialStatusConstant.PRINTED.name(), datashareUrl);
            isTransactionSuccessful = true;
        } catch (QrcodeGenerationException e) {
            description.setMessage(PlatformErrorMessages.PRT_PRT_QR_CODE_GENERATION_ERROR.getMessage());
            description.setCode(PlatformErrorMessages.PRT_PRT_QR_CODE_GENERATION_ERROR.getCode());
            printLogger.error(PlatformErrorMessages.PRT_PRT_QRCODE_NOT_GENERATED.name(), e);
            throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
                    e.getErrorText());

        } catch (UINNotFoundInDatabase e) {
            description.setMessage(PlatformErrorMessages.PRT_PRT_UIN_NOT_FOUND_IN_DATABASE.getMessage());
            description.setCode(PlatformErrorMessages.PRT_PRT_UIN_NOT_FOUND_IN_DATABASE.getCode());

            printLogger.error(
                    PlatformErrorMessages.PRT_PRT_UIN_NOT_FOUND_IN_DATABASE.name(), e);
            throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
                    e.getErrorText());

        } catch (TemplateProcessingFailureException e) {
            description.setMessage(PlatformErrorMessages.PRT_TEM_PROCESSING_FAILURE.getMessage());
            description.setCode(PlatformErrorMessages.PRT_TEM_PROCESSING_FAILURE.getCode());

            printLogger.error(PlatformErrorMessages.PRT_TEM_PROCESSING_FAILURE.name(), e);
            throw new TemplateProcessingFailureException(PlatformErrorMessages.PRT_TEM_PROCESSING_FAILURE.getMessage());

        } catch (PDFGeneratorException e) {
            description.setMessage(PlatformErrorMessages.PRT_PRT_PDF_NOT_GENERATED.getMessage());
            description.setCode(PlatformErrorMessages.PRT_PRT_PDF_NOT_GENERATED.getCode());

            printLogger.error(PlatformErrorMessages.PRT_PRT_PDF_NOT_GENERATED.name(), e);
            throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
                    e.getErrorText());

        } catch (PDFSignatureException e) {
            description.setMessage(PlatformErrorMessages.PRT_PRT_PDF_SIGNATURE_EXCEPTION.getMessage());
            description.setCode(PlatformErrorMessages.PRT_PRT_PDF_SIGNATURE_EXCEPTION.getCode());

            printLogger.error(PlatformErrorMessages.PRT_PRT_PDF_SIGNATURE_EXCEPTION.name(), e);
            throw new PDFSignatureException(PlatformErrorMessages.PRT_PRT_PDF_SIGNATURE_EXCEPTION.getMessage());
        } catch (Exception ex) {
            description.setMessage(PlatformErrorMessages.PRT_PRT_PDF_GENERATION_FAILED.getMessage());
            description.setCode(PlatformErrorMessages.PRT_PRT_PDF_GENERATION_FAILED.getCode());
            printLogger.error(ex.getMessage(), ex);
            throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
                    ex.getMessage(), ex);

        } finally {
            String eventId = "";
            String eventName = "";
            String eventType = "";
            if (isTransactionSuccessful) {
                description.setMessage(PlatformSuccessMessages.RPR_PRINT_SERVICE_SUCCESS.getMessage());
                description.setCode(PlatformSuccessMessages.RPR_PRINT_SERVICE_SUCCESS.getCode());

                eventId = EventId.RPR_402.toString();
                eventName = EventName.UPDATE.toString();
                eventType = EventType.BUSINESS.toString();
            } else {
                printStatusUpdate(requestId, CredentialStatusConstant.ERROR.name(), null);
                description.setMessage(PlatformErrorMessages.PRT_PRT_PDF_GENERATION_FAILED.getMessage());
                description.setCode(PlatformErrorMessages.PRT_PRT_PDF_GENERATION_FAILED.getCode());

                eventId = EventId.RPR_405.toString();
                eventName = EventName.EXCEPTION.toString();
                eventType = EventType.SYSTEM.toString();
            }
            /** Module-Id can be Both Success/Error code */
            String moduleId = isTransactionSuccessful ? PlatformSuccessMessages.RPR_PRINT_SERVICE_SUCCESS.getCode()
                    : description.getCode();
            String moduleName = ModuleName.PRINT_SERVICE.toString();
            auditLogRequestBuilder.createAuditRequestBuilder(description.getMessage(), eventId, eventName, eventType,
                    moduleId, moduleName, uin);
        }
        printLogger.debug("PrintServiceImpl::getDocuments()::exit");
        return byteMap;
    }

    private void integrateMpesa(String residentEmailId, Map<String, Object> attributes, String templateLang) {
        try {
            if (!StringUtils.hasText(residentEmailId) || attributes.get("phone") == null) {
                printLogger.info("Resident email Id or phone number is null for MPesa Integration.");
                return;
            }
            // Subscriber creation
            //String password = RandomStringUtils.randomNumeric(mpesaPasswordLength);
            MpesaRequest request = getSubscriberRequest(residentEmailId, subsPassword, attributes);
            printLogger.info("Account creation request.{}", request.toString());
            MpesaResponse response = restTemplate.postForObject(mpesaAccountCreationUrl, request, MpesaResponse.class);
            // Agent Login
            if (response !=null && "success".equalsIgnoreCase(response.getStatus())) {
                MpesaRequest agentRequest = new MpesaRequest();
                agentRequest.setEmail(mpesaAgentUserName);
                agentRequest.setPassword(mpesaAgentPassword);
                UserDto loginResponse = restTemplate.postForObject(mpesaAgentLoginUrl, agentRequest, UserDto.class);
                if (loginResponse != null && StringUtils.hasText(loginResponse.getToken())) {
                    // Amount deposit to subscriber account
                    MpesaTransaction transactionReq = new MpesaTransaction();
                    transactionReq.setAmount(mpesaDepositAmount);
                    transactionReq.setAccountNo(request.getEmail());
                    transactionReq.setCustomerType("subscriber");

                    HttpHeaders headers = new HttpHeaders();
                    String token = BEARER + loginResponse.getToken();
                    headers.add("authorization", token);
                    HttpEntity<MpesaTransaction> httpEntity = new HttpEntity<>(transactionReq, headers);
                    ResponseEntity<MpesaResponse> responseEntity = restTemplate.exchange(mpesaAccountDepositUrl, HttpMethod.POST, httpEntity, MpesaResponse.class);
                    // Email Notification
                    if (responseEntity != null && responseEntity.getBody() != null
                            && "success".equalsIgnoreCase(responseEntity.getBody().getStatus())) {
                        printLogger.info("Account Created and the details sent successfully via Email, " +
                                "server response..{}", responseEntity.getBody().toString());
                        sendNotificationEmail(residentEmailId, attributes, templateLang);
                    }
                }
            }
        } catch (RestClientException rClient) {
            printLogger.error("Account creation failed for .{}", residentEmailId, rClient);
        } catch (Exception e) {
            printLogger.error("Failed to send account creation notification email.{}", residentEmailId, e);
        }
    }

    private MpesaRequest getSubscriberRequest(String residentEmailId, String password, Map<String, Object> attributes) {
        MpesaRequest request = new MpesaRequest();
        supportedLang.forEach(lang -> setValue(request, lang,attributes));
        //String userName = residentEmailId.substring(0, residentEmailId.indexOf('@')) + subsUserNameSuffix;
        attributes.put("mpesaUserName", residentEmailId);
        attributes.put("mpesaPassword", password);
        request.setEmail(residentEmailId);
        request.setPhoneNumber(attributes.get("phone").toString());
        request.setPassword(password);
        return request;
    }

    private void setValue(MpesaRequest request, String lang, Map<String, Object> attributes) {
        Object firstName = attributes.get("firstName_" + lang);
        Object lastName = attributes.get("lastName_" + lang);
        if (firstName != null
                && StringUtils.hasText(firstName.toString())) {
            request.setFirstName(firstName.toString());
        }
        if (lastName != null
                && StringUtils.hasText(lastName.toString())) {
            request.setLastName(lastName.toString());
        }
    }

    private String getDatashareUrl(byte[] data) throws IOException, DataShareException, ApiNotAccessibleException {
        return dataShareUtil.getDataShare(data, policyId, partnerId).getUrl();
    }

    private String getRid(Object id) {
        return id.toString().split("/credentials/")[1];
    }

    private void sendNotificationEmail(String residentEmailId, Map<String, Object> attributes, String templateLang) throws Exception {
        try {
            List<String> emailIds = Arrays.asList(residentEmailId, defaultEmailIds);
            List<NotificationResponseDTO>  notificationResponseDTOs = notificationUtil.emailNotification(emailIds,null,
                    ACCT_EMAIL, ACCT_EMAIL_SUB, attributes, null, preferredLang);
            notificationResponseDTOs.forEach(responseDTO ->
                printLogger.info("Account creation notification sent successfully via Email, server response..{}", responseDTO)
            );
        } catch (Exception e) {
            printLogger.error("Failed to send Account creation notification.{}", residentEmailId, e);
            throw e;
        }
    }

    private void sendUINInEmail(String residentEmailId, String fileName, Map<String, Object> attributes, byte[] pdfbytes, String templateLang) {
        if (pdfbytes != null) {
            try {
                List<String> emailIds = Arrays.asList(residentEmailId, defaultEmailIds);
                List<NotificationResponseDTO> responseDTOs = notificationUtil.emailNotification(emailIds, fileName,
                        UIN_CARD_EMAIL, UIN_CARD_EMAIL_SUB, attributes, pdfbytes, preferredLang);
                responseDTOs.forEach(responseDTO ->
                        printLogger.info("UIN sent successfully via Email, server response..{}", responseDTO)
                );
            } catch (Exception e) {
                printLogger.error("Failed to send pdf UIN via email.{}", residentEmailId, e);
            }
        }
    }

    /**
     * Creates the text file.
     *
     * @param jsonString the attributes
     * @return the byte[]
     * @throws IOException Signals that an I/O exception has occurred.
     */
    private byte[] createTextFile(String jsonString) throws IOException {

        LinkedHashMap<String, String> printTextFileMap = new LinkedHashMap<>();
        JSONObject demographicIdentity = JsonUtil.objectMapperReadValue(jsonString, JSONObject.class);
        if (demographicIdentity == null)
            throw new IdentityNotFoundException(PlatformErrorMessages.PRT_PIS_IDENTITY_NOT_FOUND.getMessage());
        String printTextFileJson = utilities.getPrintTextFileJson(utilities.getConfigServerFileStorageURL(),
                utilities.getRegistrationProcessorPrintTextFile());
        JSONObject printTextFileJsonObject = JsonUtil.objectMapperReadValue(printTextFileJson, JSONObject.class);
        Set<String> printTextFileJsonKeys = printTextFileJsonObject.keySet();
        for (String key : printTextFileJsonKeys) {
            String printTextFileJsonString = JsonUtil.getJSONValue(printTextFileJsonObject, key);
            for (String value : printTextFileJsonString.split(",")) {
                Object object = demographicIdentity.get(value);
                if (object instanceof ArrayList) {
                    JSONArray node = JsonUtil.getJSONArray(demographicIdentity, value);
                    JsonValue[] jsonValues = JsonUtil.mapJsonNodeToJavaObject(JsonValue.class, node);
                    for (JsonValue jsonValue : jsonValues) {
                        /*
                         * if (jsonValue.getLanguage().equals(primaryLang)) printTextFileMap.put(value +
                         * "_" + primaryLang, jsonValue.getValue()); if
                         * (jsonValue.getLanguage().equals(secondaryLang)) printTextFileMap.put(value +
                         * "_" + secondaryLang, jsonValue.getValue());
                         */
                        if (supportedLang.contains(jsonValue.getLanguage()))
                            printTextFileMap.put(value + "_" + jsonValue.getLanguage(), jsonValue.getValue());

                    }

                } else if (object instanceof LinkedHashMap) {
                    JSONObject json = JsonUtil.getJSONObject(demographicIdentity, value);
                    printTextFileMap.put(value, (String) json.get(VALUE));
                } else {
                    printTextFileMap.put(value, (String) object);

                }
				
            }

        }

        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        String printTextFileString = gson.toJson(printTextFileMap);
        return printTextFileString.getBytes();
    }

    /**
     * Sets the qr code.
     *
     * @param attributes the attributes
     * @return true, if successful
     * @throws QrcodeGenerationException                          the qrcode
     *                                                            generation
     *                                                            exception
     * @throws IOException                                        Signals that an
     *                                                            I/O exception has
     *                                                            occurred.
     * @throws io.mosip.print.exception.QrcodeGenerationException
     */
    private boolean setQrCode(String qrString, Map<String, Object> attributes, boolean isPhotoSet)
            throws IOException, io.mosip.print.exception.QrcodeGenerationException {
        boolean isQRCodeSet = false;
        JSONObject qrJsonObj = JsonUtil.objectMapperReadValue(qrString, JSONObject.class);
        if (isPhotoSet) {
            qrJsonObj.remove("biometrics");
        }
        byte[] qrCodeBytes = null;

		ObjectMapper mapper = new ObjectMapper();
		JsonNode fullJson = mapper.readTree(qrJsonObj.toString());
		ObjectNode qrJson = mapper.createObjectNode();
	 //    String fullName = fullJson.at("/fullName/0/value").asText().replace("\u200C", "");
		// String fullNameEnglish = fullJson.at("/fullNameEnglish/0/value").asText().replace("\u200C", "");
		
		JsonNode fullNameNode = fullJson.path("fullName");
		String fullName = "";
		if (fullNameNode.isArray() && fullNameNode.size() > 0) {
		    fullName = fullNameNode.get(0).path("value").asText().replace("\u200C", "");
		}
		qrJson.put("fullName", fullName);
				
	   JsonNode fullNameEngNode = fullJson.path("fullNameEnglish");
		String fullNameEnglish = "";
		if (fullNameEngNode.isArray() && fullNameEngNode.size() > 0) {
			fullNameEnglish = fullNameEngNode.get(0).path("value").asText().replace("\u200C", "");
		}
		qrJson.put("fullNameEnglish", fullNameEnglish);
		
		// Extract other simple fields
		qrJson.put("phone", fullJson.path("phone").asText());
		qrJson.put("email", fullJson.path("email").asText());
		qrJson.put("dateOfBirth", fullJson.path("dateOfBirth").asText());
		qrJson.put("UID", fullJson.path("UID").asText());
		qrJson.put("UIN", fullJson.path("UIN").asText());
				
        if(isQrCodeWithLogoEnabled) {
            BufferedImage logoImage = ImageIO.read(new ByteArrayInputStream(Base64.decodeBase64(qrCodeLogo)));
            qrCodeBytes = qrCodeGenerator.generateQrCodeWithLogo(qrJson.toString(), QrVersion.V20, logoImage);
        } else {
            qrCodeBytes = qrCodeGenerator.generateQrCode(qrJson.toString(), QrVersion.V20);
        }
        if (qrCodeBytes != null) {
            String imageString = Base64.encodeBase64String(qrCodeBytes);
            attributes.put(QRCODE, "data:image/png;base64," + imageString);
            isQRCodeSet = true;
        }
        return isQRCodeSet;
    }

    /**
     * Sets the applicant photo.
     * <p>
     * the response
     *
     * @param attributes the attributes
     * @return true, if successful
     * @throws Exception the exception
     */
    private boolean setApplicantPhoto(String individualBio, Map<String, Object> attributes) throws Exception {
        String value = individualBio;
        boolean isPhotoSet = false;

        if (value != null) {
            CbeffToBiometricUtil util = new CbeffToBiometricUtil(cbeffutil);
            List<String> subtype = new ArrayList<>();
            byte[] photoByte = util.getImageBytes(value, FACE, subtype);
            if (photoByte != null) {
                String data = java.util.Base64.getEncoder().encodeToString(extractFaceImageData(photoByte));
                attributes.put(APPLICANT_PHOTO, "data:image/png;base64," + data);
                isPhotoSet = true;
            }
        }
        return isPhotoSet;
    }

    /**
     * Gets the artifacts.
     *
     * @param attribute the attribute
     * @return the artifacts
     * @throws IOException    Signals that an I/O exception has occurred.
     * @throws ParseException
     */
    @SuppressWarnings("unchecked")
    private void setTemplateAttributes(String jsonString, Map<String, Object> attribute)
            throws IOException {
        try {
            JSONObject demographicIdentity = JsonUtil.objectMapperReadValue(jsonString, JSONObject.class);
            if (demographicIdentity == null)
                throw new IdentityNotFoundException(PlatformErrorMessages.PRT_PIS_IDENTITY_NOT_FOUND.getMessage());

            String mapperJsonString = utilities.getIdentityMappingJson(utilities.getConfigServerFileStorageURL(),
                    utilities.getGetRegProcessorIdentityJson());
            JSONObject mapperJson = JsonUtil.objectMapperReadValue(mapperJsonString, JSONObject.class);
            JSONObject mapperIdentity = JsonUtil.getJSONObject(mapperJson,
                    utilities.getGetRegProcessorDemographicIdentity());

            List<String> mapperJsonKeys = new ArrayList<>(mapperIdentity.keySet());
            for (String key : mapperJsonKeys) {
                LinkedHashMap<String, String> jsonObject = JsonUtil.getJSONValue(mapperIdentity, key);
                Object obj = null;
                String values = jsonObject.get(VALUE);
                for (String value : values.split(",")) {
                    // Object object = demographicIdentity.get(value);
                    Object object = demographicIdentity.get(value);
                    if (object != null) {
                        try {
                            if (object instanceof Collection) {
                                // In order to parse the collection values, mainly for VC.
                                object = JsonUtil.writeValueAsString(object);
                            }
                            obj = new JSONParser().parse(object.toString());
                        } catch (Exception e) {
                            obj = object;
                        }

                        if (obj instanceof JSONArray) {
                            // JSONArray node = JsonUtil.getJSONArray(demographicIdentity, value);
                            JsonValue[] jsonValues = JsonUtil.mapJsonNodeToJavaObject(JsonValue.class, (JSONArray) obj);
                            for (JsonValue jsonValue : jsonValues) {
                                if (supportedLang.contains(jsonValue.getLanguage()))
                                    attribute.put(value + "_" + jsonValue.getLanguage(), jsonValue.getValue());
                            }

                        } 
						else if (object instanceof JSONObject) {
                            JSONObject json = (JSONObject) object;
							attribute.put(value, (String) json.get(VALUE));
                        } 
						else {
                            attribute.put(value, String.valueOf(object));
                        }
                    }

                }
            }

        } catch (JsonParseException | JsonMappingException e) {
            printLogger.error("Error while parsing Json file", e);
            throw new ParsingException(PlatformErrorMessages.PRT_RGS_JSON_PARSING_EXCEPTION.getMessage(), e);
        }
    }

    /**
     * Gets the password.
     *
     * @param jsonObject
     * @return
     * @throws Exception
     */
    private String getPassword(org.json.JSONObject jsonObject, String templateLang) throws ApisResourceAccessException, IOException {

        String[] attributes = env.getProperty(UINCARDPASSWORD).split("\\|");
        List<String> list = new ArrayList<>(Arrays.asList(attributes));
        Iterator<String> it = list.iterator();
        String uinCardPd = "";
        Object obj = null;
        while (it.hasNext()) {
            String key = it.next().trim();
            Object object = jsonObject.get(key);
            if (object != null) {
                try {
                    obj = new JSONParser().parse(object.toString());
                } catch (Exception e) {
                    obj = object;
                }
            }
            if (obj instanceof JSONArray) {
                JsonValue[] jsonValues = JsonUtil.mapJsonNodeToJavaObject(JsonValue.class, (JSONArray) obj);
                uinCardPd = uinCardPd.concat(getFormattedPasswordAttribute(getParameter(jsonValues, templateLang)));

            } else if (object instanceof org.json.simple.JSONObject) {
                org.json.simple.JSONObject json = (org.json.simple.JSONObject) object;
                uinCardPd = uinCardPd.concat(getFormattedPasswordAttribute((String) json.get(VALUE)));
            } else {
                uinCardPd = uinCardPd.concat(getFormattedPasswordAttribute(object.toString()));
            }
        }
        return uinCardPd;
    }

	private String getFormattedPasswordAttribute(String value) {
		String password = value.replaceAll("[^a-zA-Z0-9]+","");
		if (password.length() >= passwordLengthPerAttribute) {
			return password.substring(0, passwordLengthPerAttribute);
		} else {
			while (password.length() < passwordLengthPerAttribute) {
				password = password.repeat(2);
				if (password.length() >= passwordLengthPerAttribute) {
					password = password.substring(0, passwordLengthPerAttribute);
					break;
				}
			}
		}
		return password;
	}

    /**
     * Gets the parameter.
     *
     * @param jsonValues the json values
     * @param langCode   the lang code
     * @return the parameter
     */
    private String getParameter(JsonValue[] jsonValues, String langCode) {

        String parameter = null;
        if (jsonValues != null) {
            for (int count = 0; count < jsonValues.length; count++) {
                String lang = jsonValues[count].getLanguage();
				if ("mya".equalsIgnoreCase(lang) || "my".equalsIgnoreCase(lang)) {
				    lang = "bur";
				}
                if (langCode.contains(lang)) {
                    parameter = jsonValues[count].getValue();
                    break;
                }
            }
        }
        return parameter;
    }

    public byte[] extractFaceImageData(byte[] decodedBioValue) {

        try (DataInputStream din = new DataInputStream(new ByteArrayInputStream(decodedBioValue))) {

            byte[] format = new byte[4];
            din.read(format, 0, 4);
            byte[] version = new byte[4];
            din.read(version, 0, 4);
            int recordLength = din.readInt();
            short numberofRepresentionRecord = din.readShort();
            byte certificationFlag = din.readByte();
            byte[] temporalSequence = new byte[2];
            din.read(temporalSequence, 0, 2);
            int representationLength = din.readInt();
            byte[] representationData = new byte[representationLength - 4];
            din.read(representationData, 0, representationData.length);
            try (DataInputStream rdin = new DataInputStream(new ByteArrayInputStream(representationData))) {
                byte[] captureDetails = new byte[14];
                rdin.read(captureDetails, 0, 14);
                byte noOfQualityBlocks = rdin.readByte();
                if (noOfQualityBlocks > 0) {
                    byte[] qualityBlocks = new byte[noOfQualityBlocks * 5];
                    rdin.read(qualityBlocks, 0, qualityBlocks.length);
                }
                short noOfLandmarkPoints = rdin.readShort();
                byte[] facialInformation = new byte[15];
                rdin.read(facialInformation, 0, 15);
                if (noOfLandmarkPoints > 0) {
                    byte[] landmarkPoints = new byte[noOfLandmarkPoints * 8];
                    rdin.read(landmarkPoints, 0, landmarkPoints.length);
                }
                byte faceType = rdin.readByte();
                byte imageDataType = rdin.readByte();
                byte[] otherImageInformation = new byte[9];
                rdin.read(otherImageInformation, 0, otherImageInformation.length);
                int lengthOfImageData = rdin.readInt();

                byte[] image = new byte[lengthOfImageData];
                rdin.read(image, 0, lengthOfImageData);

                return image;
            }
        } catch (Exception ex) {
            throw new PDFGeneratorException(PDFGeneratorExceptionCodeConstant.PDF_EXCEPTION.getErrorCode(),
                    ex.getMessage() + ExceptionUtils.getStackTrace(ex));
        }
    }

    private String getCrdentialSubject(String crdential) {
        org.json.JSONObject jsonObject = new org.json.JSONObject(crdential);
        return jsonObject.get("credentialSubject").toString();
    }

    private void printStatusUpdate(String requestId, String status, String datashareUrl) {

        CredentialStatusEvent creEvent = new CredentialStatusEvent();
        LocalDateTime currentDtime = DateUtils.getUTCCurrentDateTime();
        StatusEvent sEvent = new StatusEvent();
        sEvent.setId(UUID.randomUUID().toString());
        sEvent.setRequestId(requestId);
        sEvent.setStatus(status);
        if (datashareUrl != null) {
            sEvent.setUrl(datashareUrl);
        }
        sEvent.setTimestamp(Timestamp.valueOf(currentDtime).toString());
        creEvent.setPublishedOn(new DateTime().toString());
        creEvent.setPublisher("PRINT_SERVICE");
        creEvent.setTopic(topic);
        creEvent.setEvent(sEvent);
        webSubSubscriptionHelper.printStatusUpdateEvent(topic, creEvent);
    }

    public org.json.JSONObject decryptAttribute(org.json.JSONObject data, String encryptionPin, String credential)
            throws ParseException {

        // org.json.JSONObject jsonObj = new org.json.JSONObject(credential);
        JSONParser parser = new JSONParser(); // this needs the "json-simple" library
        Object obj = parser.parse(credential);
        JSONObject jsonObj = (org.json.simple.JSONObject) obj;

        JSONArray jsonArray = (JSONArray) jsonObj.get("protectedAttributes");
        if (Objects.isNull(jsonArray)) {
            return data;
        }
        for (Object str : jsonArray) {

            CryptoWithPinRequestDto cryptoWithPinRequestDto = new CryptoWithPinRequestDto();
            CryptoWithPinResponseDto cryptoWithPinResponseDto = new CryptoWithPinResponseDto();

            cryptoWithPinRequestDto.setUserPin(encryptionPin);
            cryptoWithPinRequestDto.setData(data.getString(str.toString()));
            try {
                cryptoWithPinResponseDto = cryptoUtil.decryptWithPin(cryptoWithPinRequestDto);
            } catch (InvalidKeyException | NoSuchAlgorithmException | InvalidKeySpecException
                    | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
                printLogger.error("Error while decrypting the data", e);
                throw new CryptoManagerException(PlatformErrorMessages.PRT_INVALID_KEY_EXCEPTION.getCode(),
                        PlatformErrorMessages.PRT_INVALID_KEY_EXCEPTION.getMessage(), e);
            }
            data.put((String) str, cryptoWithPinResponseDto.getData());

        }

        return data;
    }
	private String convertTextToImageFile(String text, String fileName) throws Exception {

    
    int width = 600;
    int height = 120;

    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2d = image.createGraphics();

    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    g2d.setColor(Color.WHITE);
    g2d.fillRect(0, 0, width, height);

    // Font font = Font.createFont(Font.TRUETYPE_FONT,
    //         new File("/home/mosip/fonts/NotoSansMyanmar-Regular.ttf"))
    //         .deriveFont(40f);

	 Font baseFont = Font.createFont(
            Font.TRUETYPE_FONT,
            new File("/home/mosip/fonts/NotoSansMyanmar-Regular.ttf")
    );
    // Bigger + Bold font
    Font font = baseFont.deriveFont(Font.BOLD, 55f);

    g2d.setFont(font);
    g2d.setColor(Color.BLACK);

    FontMetrics fm = g2d.getFontMetrics();
    int x = 10;
    int y = ((height - fm.getHeight()) / 2) + fm.getAscent();

    g2d.drawString(text, x, y);
    g2d.dispose();

    // Store inside POD temp directory
    File outputFile = new File("/tmp/" + fileName + ".png");
    ImageIO.write(image, "png", outputFile);

    return outputFile.getAbsolutePath();
}
}
