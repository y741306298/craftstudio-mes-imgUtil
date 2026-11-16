package com.craftstudio.imgutil.infra.persistence;

import com.craftstudio.imgutil.domain.graphics.CallbackConfig;
import com.craftstudio.imgutil.domain.task.*;
import com.craftstudio.imgutil.infra.config.GraphicsProperties;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.DependsOn;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.Optional;
import java.util.function.Consumer;

@Repository
@DependsOn("dataDirectoryLock")
public class FileCallbackRepository implements CallbackRepository {
    private final Path root;
    private final JsonMapper mapper;
    public FileCallbackRepository(GraphicsProperties properties,JsonMapper mapper)throws Exception{
        root=Path.of(properties.dataDirectory()).toAbsolutePath().resolve("callbacks");Files.createDirectories(root);this.mapper=mapper;
    }
    private Path path(String id){if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))throw new IllegalArgumentException("回调任务编号无效");return root.resolve(id+".json");}
    @Override public synchronized void register(String id,CallbackConfig config){if(find(id).isEmpty())save(CallbackDelivery.pending(id,config));}
    @Override public Optional<CallbackDelivery> find(String id){Path path=path(id);if(!Files.exists(path))return Optional.empty();try{return Optional.of(mapper.readValue(path.toFile(),CallbackDelivery.class));}catch(Exception e){throw new IllegalStateException("读取回调投递记录失败",e);}}
    @Override public synchronized void save(CallbackDelivery delivery){
        Path target=path(delivery.taskId()),temporary=root.resolve(delivery.taskId()+".tmp");
        try{mapper.writeValue(temporary.toFile(),delivery);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){throw new IllegalStateException("保存回调投递记录失败",e);}
    }
    @Override public void delete(String id){try{Files.deleteIfExists(path(id));}catch(Exception e){throw new IllegalStateException("清理回调记录失败",e);}}
    @Override public void forEach(Consumer<CallbackDelivery> action){
        try(var files=Files.newDirectoryStream(root,"*.json")){for(Path file:files)action.accept(mapper.readValue(file.toFile(),CallbackDelivery.class));}
        catch(Exception e){throw new IllegalStateException("遍历回调记录失败",e);}
    }
}
