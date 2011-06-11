package be.cytomine.command

import ij.ImagePlus
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import ij.plugin.filter.Binary
import imagej.DynamicThreshold
import ij.process.ImageConverter
import imagej.Multi_OtsuThreshold

/**
 * Cytomine @ GIGA-ULG
 * User: stevben
 * Date: 1/06/11
 * Time: 13:44
 */
class ProxyController {

    def binary = {
        def split = request.queryString.split("http://")
        def url = "http://" + split[1]
        def out = new ByteArrayOutputStream()
        out << new URL(url).openStream()
        InputStream inputStream = new ByteArrayInputStream(out.toByteArray());
        BufferedImage bufferedImage = ImageIO.read(inputStream);
        ImagePlus ip = new ImagePlus(url,bufferedImage)
        ImageConverter ic = new ImageConverter(ip)
        ic.convertToGray8()
        DynamicThreshold dt = new DynamicThreshold()
        dt.setup(url, ip)
        dt.run(ip.getProcessor())
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(dt.getImMeanMaxMin().getBufferedImage(), "jpg", baos);
        byte[] bytesOut = baos.toByteArray();
        response.contentLength = baos.size();
        withFormat {
            jpg {
                if (request.method == 'HEAD') {
                    render(text: "", contentType: "image/jpeg");
                }
                else {
                    response.contentType = "image/jpeg"; response.getOutputStream() << bytesOut
                }
            }
        }
    }

    def otsu = {

        def split = request.queryString.split("http://")
        def url = "http://" + split[1]
        def out = new ByteArrayOutputStream()
        out << new URL(url).openStream()

        InputStream inputStream = new ByteArrayInputStream(out.toByteArray());
        BufferedImage bufferedImage;

        bufferedImage = ImageIO.read(inputStream);
        ImagePlus ip = new ImagePlus(url,bufferedImage)
        ImageConverter ic = new ImageConverter(ip)

        ic.convertToGray8()
        Multi_OtsuThreshold dt = new Multi_OtsuThreshold()
        dt.setup(url, ip)
        dt.run(ip.getProcessor())
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(dt.getResult().getBufferedImage(), "jpg", baos);
        byte[] bytesOut = baos.toByteArray();
        response.contentLength = baos.size();
        response.setHeader("Connection","Keep-Alive")
                response.setHeader("Accept-Ranges","bytes")
                response.setHeader("Content-Type", "image/jpeg")

        withFormat {
            jpg {
                if (request.method == 'HEAD') {
                    render(text: "", contentType: "image/jpeg");
                }
                else {
                    response.contentType = "image/jpeg";
                    response.getOutputStream() << bytesOut
                    out.flush()
                    response.getOutputStream().flush()
                }
            }
        }




    }
}
