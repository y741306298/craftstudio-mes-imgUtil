package com.craftstudio.imgutil.domain.graphics;

import java.net.URI;

public record CallbackConfig(String callbackUrl,Object callbackCustomValue) {
    public void validate(){
        try{
            URI uri=URI.create(callbackUrl);
            if(callbackUrl.length()>4096 || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null
                    || !("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme())))throw new IllegalArgumentException();
        }catch(Exception e){throw new IllegalArgumentException("callbackUrl 必须为有效 HTTP(S) 地址",e);}
    }
}
