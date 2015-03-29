package com.innowhere.relproxy.impl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import com.innowhere.relproxy.RelProxyOnReloadListener;

/**
 *
 * @author jmarranz
 */
public abstract class GenericProxyImpl
{
    protected RelProxyOnReloadListener reloadListener;
    
    public GenericProxyImpl()
    {
    }

    protected void init(GenericProxyConfigBaseImpl config)
    {
        this.reloadListener = config.getRelProxyOnReloadListener(); 
    }    
    
    public RelProxyOnReloadListener getRelProxyOnReloadListener()
    {
        return reloadListener;
    }
    
    public <T> T create(T obj,Class<T> clasz)
    {       
        if (obj == null) return null;   
        
        return (T)create(obj,new Class[] { clasz });
    }
  
    public Object create(Object obj,Class[] classes)
    {       
        if (obj == null) return null;   
        
        InvocationHandler handler = createGenericProxyInvocationHandler(obj);
        
        Object proxy = Proxy.newProxyInstance(obj.getClass().getClassLoader(),classes, handler);   
        return proxy;
    }    
            
            
    public abstract GenericProxyInvocationHandler createGenericProxyInvocationHandler(Object obj);    
}
