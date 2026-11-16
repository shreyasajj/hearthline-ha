import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** Creates ONLY a synthetic, non-authenticating token QR for manual import checks. */
public final class GenerateTokenQrFixture {
    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
    public static void main(String[] args) throws Exception {
        String token = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "." +
            encode("{\"iss\":\"synthetic-test-only\",\"iat\":0,\"exp\":9999999999}") + "." +
            encode("synthetic signature, not a real HA credential");
        var matrix = new MultiFormatWriter().encode(token, BarcodeFormat.QR_CODE, 512, 512,
            Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.Q));
        var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<512;y++) for(int x=0;x<512;x++) {
            boolean logoSpace = x>=171 && x<=340 && y>=171 && y<=340;
            image.setRGB(x,y,!logoSpace && matrix.get(x,y) ? 0xff000000 : 0xffffffff);
        }
        ImageIO.write(image,"PNG",new File(args[0]));
        System.out.println("Created synthetic token QR fixture; no valid credential.");
    }
}
