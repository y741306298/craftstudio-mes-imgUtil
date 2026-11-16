package com.craftstudio.imgutil.application.command;

import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.*;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent retryable callback outbox, separate from rendering worker slots. */
@Service
public class GraphicsCallbackDispatcher {
    private static final Logger log=LoggerFactory.getLogger(GraphicsCallbackDispatcher.class);
    private final CallbackRepository callbacks;
    private final JobRepository jobs;
    private final GraphicsProperties properties;
    private final JsonMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("graphics-callback").factory());
    private final AtomicBoolean busy=new AtomicBoolean();
    public GraphicsCallbackDispatcher(CallbackRepository callbacks,JobRepository jobs,GraphicsProperties properties,JsonMapper mapper){this.callbacks=callbacks;this.jobs=jobs;this.properties=properties;this.mapper=mapper;}
    public record Result(String taskId,Artifact artifact){}
    public record Payload(String id,String status,Result result,String error,String errorCode,Object callbackCustomValue){}
    @Scheduled(fixedDelayString="${graphics.callback-dispatch-ms:1000}")
    public void dispatch(){
        if(!busy.compareAndSet(false,true))return;
        try{worker.execute(()->{try{deliverDue();}catch(Exception e){log.error("图形回调调度失败",e);}finally{busy.set(false);}});}
        catch(RejectedExecutionException e){busy.set(false);}
    }
    public void deliverDue(){
        int[] processed={0};
        callbacks.forEach(delivery->{
            if(processed[0]>=16 || !"PENDING".equals(delivery.status()) || delivery.nextAttemptAt()>System.currentTimeMillis())return;
            var job=jobs.find(delivery.taskId());
            if(job.isEmpty()||!job.get().status().terminal())return;
            processed[0]++;deliver(delivery,job.get());
        });
    }
    private void deliver(CallbackDelivery delivery,GraphicsJob job){
        int attempts=delivery.attempts()+1;
        try{
            if(properties.apiKey()==null||properties.apiKey().isBlank())throw new IllegalStateException("回调签名密钥未配置");
            boolean success=job.status()==JobStatus.SUCCEEDED;
            String error=job.message();if(error!=null&&error.length()>1024)error=error.substring(0,1024);
            Payload payload=new Payload(job.taskId(),success?"success":"error",success?new Result(job.taskId(),job.artifact()):null,error,job.errorCode(),delivery.config().callbackCustomValue());
            byte[] body=mapper.writeValueAsBytes(payload);
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(properties.apiKey().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String signature=HexFormat.of().formatHex(mac.doFinal(body));
            var request=HttpRequest.newBuilder(URI.create(delivery.config().callbackUrl())).timeout(Duration.ofSeconds(10))
                    .header("Content-Type","application/json").header("X-Graphics-Signature",signature).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            int status=http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
            if(status<200||status>=300)throw new IllegalStateException("HTTP "+status);
            callbacks.save(new CallbackDelivery(job.taskId(),delivery.config(),attempts,0,"DELIVERED",null));
        }catch(Exception e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            long delay=Math.min(300000L,5000L*(1L<<Math.min(attempts-1,6)));
            callbacks.save(new CallbackDelivery(job.taskId(),delivery.config(),attempts,System.currentTimeMillis()+delay,attempts>=10?"FAILED":"PENDING",e.getMessage()));
            log.warn("图形回调投递失败: taskId={}, attempt={}, error={}",job.taskId(),attempts,e.getMessage());
        }
    }
    @PreDestroy public void close(){worker.shutdownNow();http.close();}
}
