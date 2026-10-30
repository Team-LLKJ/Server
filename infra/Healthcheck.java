import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class Healthcheck {
 public static void main(String[] args) {
  try {
   String port=System.getenv().getOrDefault("SERVER_PORT",System.getenv().getOrDefault("PORT","5174"));
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(4)).GET().build();
   var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
   System.exit(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200?0:1);
  } catch(Exception e) {System.exit(1);}
 }
}
