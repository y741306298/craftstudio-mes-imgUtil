package com.craftstudio.imgutil.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.awt.*;
import java.nio.file.*;
import java.util.*;

@Component
public class GraphicsFontRegistry {
    public GraphicsFontRegistry(@Value("${graphics.font-directory:}")String directory,@Value("${graphics.required-fonts:}")String required)throws Exception{
        GraphicsEnvironment environment=GraphicsEnvironment.getLocalGraphicsEnvironment();
        if(!directory.isBlank()){
            Path root=Path.of(directory);
            if(!Files.isDirectory(root))throw new IllegalArgumentException("图形字体目录不存在");
            try(var files=Files.list(root)){
                for(Path file:files.filter(Files::isRegularFile).filter(p->p.toString().toLowerCase(Locale.ROOT).matches(".*\\.(ttf|otf|ttc)$")).toList()){
                    for(Font font:Font.createFonts(file.toFile()))environment.registerFont(font);
                }
            }
        }
        Set<String> available=new HashSet<>(Arrays.asList(environment.getAvailableFontFamilyNames()));
        for(String family:required.split(","))if(!family.isBlank()&&!available.contains(family.trim()))throw new IllegalArgumentException("缺少生产字体: "+family.trim());
    }
}
