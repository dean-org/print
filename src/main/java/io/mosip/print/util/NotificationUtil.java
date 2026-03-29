package io.mosip.print.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.print.core.http.ResponseWrapper;
import io.mosip.print.dto.ErrorDTO;
import io.mosip.print.dto.NotificationResponseDTO;
import io.mosip.print.exception.ApisResourceAccessException;
import io.mosip.print.exception.NotificationException;
import io.mosip.print.logger.PrintLogger;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class NotificationUtil {

    private Logger log = PrintLogger.getLogger(NotificationUtil.class);

    @Autowired
    private RestApiClient restApiClient;

    @Value("${emailResource.url}")
    private String emailResourceUrl;

    @Autowired
    private TemplateGenerator templateGenerator;

    @Autowired
    private ObjectMapper mapper;

    @Value("${mosip.utc-datetime-pattern}")
    private String dateTimeFormat;

    private static final String EMAIL_SUB_DEFAULT = "UID Card Attached!";
    private static final String EMAIL_DEFAULT = "Dear Citizen,<br><br>" + "Please find your UID Card attached.\n\n" + "Regards,\n" + "e-ID Department";

    private static final String EMAIL_SUB_DEFAULT_BUR = "အိုင်ဒီကတ်ကို ပူးတွဲထားပါသည်။";
    private static final String EMAIL_DEFAULT_BUR = "ချစ်လှစွာသော နိုင်ငံသား၊\n\n" + "သင့် UID ကတ်ကို ပူးတွဲပေးပါသည်။\n" + "ကျေးဇူးပြု၍ ကြည့်ပါ။\n\n" + "ဂါရဝပြုလျက်ဖြင့်၊\n" + "အီလက်ထရောနစ်မှတ်ပုံတင်စနစ်ဦးစီးဌာန";

    public List<NotificationResponseDTO> emailNotification(List<String> emailIds, String fileName, String emailContentTpl, String emailSubTpl, Map<String, Object> attributes,
                                                    byte[] attachmentFile, String templateLang) throws Exception {
        log.info("sessionId", "idType", "id", "In emailNotification method of NotificationUtil service");
        HttpEntity<byte[]> doc = null;
        String fileText = null;
        MultiValueMap<Object, Object> emailMap = new LinkedMultiValueMap<>();
        if (attachmentFile != null) {
            LinkedMultiValueMap<String, String> pdfHeaderMap = new LinkedMultiValueMap<>();
            pdfHeaderMap.add("Content-disposition",
                    "form-data; name=attachments; filename=" + fileName + ".pdf");
            pdfHeaderMap.add("Content-type", "application/pdf");
            doc = new HttpEntity<>(attachmentFile, pdfHeaderMap);
            emailMap.add("attachments", doc);
        }
        List<NotificationResponseDTO> notifierResponseList = new ArrayList<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        emailMap.add("mailContent", getEmailContent(emailContentTpl, attributes, templateLang));
        emailMap.add("mailSubject", getEmailSubject(emailSubTpl, attributes, templateLang));

        log.info("sessionId", "idType", "id",
                "In emailNotification method of NotificationUtil service emailResourceUrl: " + emailResourceUrl);
        emailIds.forEach(emailId -> {
            try {
                notifierResponseList.add(sendEmail(emailId, headers, emailMap));
            } catch (Exception e) {
                log.error("Failed to send notification via email.{}", emailId, e);
            }
        });
        return notifierResponseList;
    }

    private NotificationResponseDTO sendEmail( String emailId, HttpHeaders headers, MultiValueMap<Object, Object> emailMap) throws Exception {
        NotificationResponseDTO notifierResponse = new NotificationResponseDTO();
        try {
            emailMap.set("mailTo", emailId);
            HttpEntity<MultiValueMap<Object, Object>> httpEntity = new HttpEntity<>(emailMap, headers);
            ResponseWrapper<?> responseWrapper = (ResponseWrapper<?>) restApiClient.postApi(emailResourceUrl,
                    MediaType.MULTIPART_FORM_DATA, httpEntity, ResponseWrapper.class);
            if (responseWrapper != null) {
                if (responseWrapper.getErrors() != null && !responseWrapper.getErrors().isEmpty()) {
                    ErrorDTO error = responseWrapper.getErrors().get(0);
                    throw new NotificationException(error.getMessage());
                }
                notifierResponse = mapper.readValue(mapper.writeValueAsString(responseWrapper.getResponse()),
                        NotificationResponseDTO.class);
            }
        } catch (Exception e) {
            log.error("Error while sending pdf email.", e);
            throw e;
        }
        return notifierResponse;
    }

    private String getEmailContent(String emailContentTpl, Map<String, Object> attributes, String preferredLang) throws IOException, ApisResourceAccessException {

        InputStream in = templateGenerator.getTemplate(emailContentTpl, attributes, preferredLang);
        log.info("preferredLang: {}", preferredLang);
        if (in == null && Objects.equals(preferredLang, "eng")) {
            return EMAIL_DEFAULT;
        }
        else if (in == null) {
            return EMAIL_DEFAULT_BUR;
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String getEmailSubject(String emailSubTpl, Map<String, Object> attributes, String templateLang) throws IOException, ApisResourceAccessException {

        InputStream in = templateGenerator.getTemplate(emailSubTpl, attributes, templateLang);
        log.info("templateLang: {}", templateLang);
        if (in == null && Objects.equals(templateLang, "eng")) {
            return EMAIL_SUB_DEFAULT;
        }
        else if (in == null) {
            return EMAIL_SUB_DEFAULT_BUR;
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String getCurrentResponseTime() {
        log.info("sessionId", "idType", "id", "In getCurrentResponseTime method of NotificationUtil service");
        return DateUtils.formatDate(new Date(System.currentTimeMillis()), dateTimeFormat);
    }
}

