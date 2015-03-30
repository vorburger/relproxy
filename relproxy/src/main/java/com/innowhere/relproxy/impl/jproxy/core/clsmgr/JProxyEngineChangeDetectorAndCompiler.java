package com.innowhere.relproxy.impl.jproxy.core.clsmgr;

import com.innowhere.relproxy.impl.jproxy.core.clsmgr.srcunit.SourceUnit;
import com.innowhere.relproxy.impl.jproxy.core.clsmgr.srcunit.SourceFileJavaNormal;
import com.innowhere.relproxy.impl.jproxy.core.clsmgr.cldesc.ClassDescriptorSourceUnit;
import com.innowhere.relproxy.impl.jproxy.core.clsmgr.cldesc.ClassDescriptor;
import com.innowhere.relproxy.impl.jproxy.core.clsmgr.cldesc.ClassDescriptorSourceFileRegistry;
import com.innowhere.relproxy.impl.jproxy.core.clsmgr.cldesc.ClassDescriptorInner;
import com.innowhere.relproxy.impl.jproxy.JProxyUtil;
import com.innowhere.relproxy.impl.jproxy.core.clsmgr.comp.JProxyCompilerContext;
import com.innowhere.relproxy.impl.jproxy.core.clsmgr.comp.JProxyCompilerInMemory;
import com.innowhere.relproxy.jproxy.JProxyCompilerListener;
import com.innowhere.relproxy.jproxy.JProxyDiagnosticsListener;
import com.innowhere.relproxy.jproxy.JProxyInputSourceFileExcludedListener;
import java.io.File;
import java.util.LinkedList;

/**
 *
 * @author jmarranz
 */
public class JProxyEngineChangeDetectorAndCompiler
{
    protected JProxyEngine engine;
    protected JProxyCompilerInMemory compiler; 
    protected FolderSourceList folderSourceList;    
    protected FolderSourceList requiredExtraJarPaths;
    protected String folderClasses; // Puede ser nulo (es decir NO salvar como .class los cambios)    
    protected JProxyInputSourceFileExcludedListener excludedListener;    
    protected JavaSourcesSearch sourcesSearch;
    protected JProxyCompilerListener compilerListener;    
    protected ClassDescriptorSourceFileRegistry sourceRegistry;
    
    public JProxyEngineChangeDetectorAndCompiler(JProxyEngine engine, FolderSourceList folderSourceList,FolderSourceList requiredExtraJarPaths,
            String folderClasses, JProxyInputSourceFileExcludedListener excludedListener,Iterable<String> compilationOptions,JProxyDiagnosticsListener diagnosticsListener,
            JProxyCompilerListener compilerListener)
    {
        this.engine = engine;
        this.folderSourceList = folderSourceList; 
        this.requiredExtraJarPaths = requiredExtraJarPaths;
        this.folderClasses = folderClasses;
        this.excludedListener = excludedListener;
        this.compiler = new JProxyCompilerInMemory(this,compilationOptions,diagnosticsListener);         
        this.sourcesSearch = new JavaSourcesSearch(this); 
        this.compilerListener = compilerListener;
    }
    
    public JProxyEngine getJProxyEngine()
    {
        return engine;
    }
        
    public FolderSourceList getFolderSourceList()
    {
        return folderSourceList;
    }    
    
    public FolderSourceList getRequiredExtraJarPaths()
    {
        return requiredExtraJarPaths;
    }        
    
    public JProxyInputSourceFileExcludedListener getJProxyInputSourceFileExcludedListener()
    {
        return excludedListener;
    }    
    
    public ClassDescriptorSourceFileRegistry getClassDescriptorSourceFileRegistry()
    {
        return sourceRegistry;
    }    
    
    public ClassDescriptor getClassDescriptor(String className)
    {
        return sourceRegistry.getClassDescriptor(className);
    }    
    
    private boolean isSaveClassesMode()
    {
        return (folderClasses != null);
    }    
    
    private JProxyCompilerListener getJProxyCompilerListener()
    {
        return compilerListener;
    }
    
    private void cleanBeforeCompile(ClassDescriptorSourceUnit sourceFile)
    {
        if (isSaveClassesMode()) 
            deleteClasses(sourceFile); // Antes de que nos las carguemos en memoria la clase principal y las inner tras recompilar
            
        sourceFile.cleanOnSourceCodeChanged(); // El código fuente nuevo puede haber cambiado totalmente las innerclasses antiguas (añadido, eliminado) y por supuesto el bytecode necesita olvidarse   
    }
    
    private void compile(ClassDescriptorSourceUnit sourceFile,JProxyCompilerContext context)
    {       
        if (sourceFile.getClassBytes() != null)
            return; // Ya ha sido compilado seguramente por dependencia de un archivo compilado inmediatamente antes, recuerda que el atributo classBytes se pone a null antes de compilar los archivos cambiados/nuevos
        
        compiler.compileSourceFile(sourceFile,context,engine.getCurrentClassLoader(),sourceRegistry);      
    }            
    
    public synchronized void detectChangesInSources()
    {
        // boolean firstTime = (sourceFileMap == null); // La primera vez sourceFileMap es null

        LinkedList<ClassDescriptorSourceUnit> updatedSourceFiles = new LinkedList<ClassDescriptorSourceUnit>();
        LinkedList<ClassDescriptorSourceUnit> newSourceFiles = new LinkedList<ClassDescriptorSourceUnit>();        
        LinkedList<ClassDescriptorSourceUnit> deletedSourceFiles = new LinkedList<ClassDescriptorSourceUnit>();
        
        ClassDescriptorSourceFileRegistry oldSourceRegistry = this.sourceRegistry; // Puede ser null (la primera vez)
        ClassDescriptorSourceFileRegistry newSourceRegistry = new ClassDescriptorSourceFileRegistry();
        
        sourcesSearch.sourceFileSearch(oldSourceRegistry,newSourceRegistry,updatedSourceFiles,newSourceFiles,deletedSourceFiles);
        
        this.sourceRegistry = newSourceRegistry;

        if (!updatedSourceFiles.isEmpty() || !newSourceFiles.isEmpty() || !deletedSourceFiles.isEmpty()) // También el hecho de eliminar una clase debe implicar crear un ClassLoader nuevo para que dicha clase desaparezca de las clases cargadas aunque será muy raro que sólo eliminemos un .java y no añadamos/cambiemos otros, otro motico es porque si tenemos configurado el autosalvado de .class tenemos que eliminar en ese caso
        {                      
            LinkedList<ClassDescriptorSourceUnit> sourceFilesToCompile = new LinkedList<ClassDescriptorSourceUnit>();
            sourceFilesToCompile.addAll(updatedSourceFiles);
            sourceFilesToCompile.addAll(newSourceFiles);            
            
            updatedSourceFiles = null; // Ya no se necesita
            newSourceFiles = null; // Ya no se necesita
            
            if (!sourceFilesToCompile.isEmpty())             
            {
                // Eliminamos el estado de la anterior compilación de todas las clases que van a recompilarse antes de compilarlas porque al compilar una clase es posible que
                // se necesite recompilar al mismo tiempo una dependiente de otra (ej clase base) y luego se intente compilar la dependiente y sería un problema que limpiáramos antes de compilar cada archivo
                for(ClassDescriptorSourceUnit sourceFile : sourceFilesToCompile)            
                    cleanBeforeCompile(sourceFile);   
                
           
                JProxyCompilerContext context = compiler.createJProxyCompilerContext();
                JProxyCompilerListener compilerListener = getJProxyCompilerListener();
                try
                {            
                    
                    for(ClassDescriptorSourceUnit sourceFile : sourceFilesToCompile)            
                    {
                        File file = null;
                        if (compilerListener != null)
                        {                           
                            SourceUnit srcUnit = sourceFile.getSourceUnit();
                            file = ((SourceFileJavaNormal)srcUnit).getFileExt().getFile();
                        }
                        
                        if (compilerListener != null && file != null)
                            compilerListener.beforeCompile(file);                        
                        
                        compile(sourceFile,context);        
                        
                        if (compilerListener != null && file != null)
                            compilerListener.afterCompile(file);                        
                    }
                }
                finally
                {
                    context.close();
                }
                
                if (isSaveClassesMode())
                {
                    for(ClassDescriptorSourceUnit sourceFile : sourceFilesToCompile)            
                    {
                        saveClasses(sourceFile);                     
                    }                
                }
            }

            if (isSaveClassesMode() && !deletedSourceFiles.isEmpty())
                for(ClassDescriptorSourceUnit sourceFile : deletedSourceFiles)
                    deleteClasses(sourceFile);                     
            
            deletedSourceFiles = null; // Ya no se necesita
                          
            engine.setNeedReload(true);
        }
    }    
    
    private void saveClasses(ClassDescriptorSourceUnit sourceFile)
    {
        // Salvamos la clase principal
        {
            File classFilePath = ClassDescriptor.getAbsoluteClassFilePathFromClassNameAndClassPath(sourceFile.getClassName(),folderClasses);
            JProxyUtil.saveFile(classFilePath,sourceFile.getClassBytes());
        }

        // Salvamos las innerclasses si hay, no hay problema de clases inner no detectadas pues lo están todas pues sólo se salva tras una compilación
        LinkedList<ClassDescriptorInner> innerClassDescList = sourceFile.getInnerClassDescriptors();            
        if (innerClassDescList != null && !innerClassDescList.isEmpty())
        {
            for(ClassDescriptorInner innerClassDesc : innerClassDescList)
            {
                File classFilePath = ClassDescriptor.getAbsoluteClassFilePathFromClassNameAndClassPath(innerClassDesc.getClassName(),folderClasses);
                JProxyUtil.saveFile(classFilePath,innerClassDesc.getClassBytes());                
            }
        }                           
    }    
    
    private void deleteClasses(ClassDescriptorSourceUnit sourceFile)
    {
        // Puede ocurrir que esta clase nunca se haya cargado y se ha modificado el código fuente y queramos limpiar los .class correspondientes pues se van a recrear
        // como no conocemos qué inner clases están asociadas para saber que .class hay que eliminar, pues lo que hacemos es directamente obtener los .class que hay 
        // en el directorio con el fin de eliminar todos .class que tengan el patrón de ser inner classes del source file de acuerdo a su nombre
        // así conseguimos por ejemplo también eliminar las local classes (inner clases con nombre declaradas dentro de un método) que no hay manera de conocer 
        // a través de la carga de la clase
        
        // Hay un caso en el que puede haber .class que ya no están en el código fuente y es cuando tocamos el código fuente ANTES de cargar y eliminamos algún .java,
        // al cargar como no existe el archivo no lo relacionamos con los .class
        // La solución sería en tiempo de carga forzar una carga de todas las clases y de ahí deducir todos los .class que deben existir (excepto las clases locales
        // que no podríamos detectarlas), pero el que haya .class sobrantes antiguos no es gran problema.
        
        File classFilePath = ClassDescriptor.getAbsoluteClassFilePathFromClassNameAndClassPath(sourceFile.getClassName(),folderClasses);        
        File parentDir = JProxyUtil.getParentDir(classFilePath);
        String[] fileNameList = parentDir.list(); // Es más ligero que listFiles() que crea File por cada resultado
        if (fileNameList != null) // Si es null es que el directorio no está creado
        {
            for (String fileName : fileNameList) 
            {
                int pos = fileName.lastIndexOf(".class");
                if (pos == -1) continue;
                String simpleClassName = fileName.substring(0, pos);
                if (sourceFile.getSimpleClassName().equals(simpleClassName) ||
                    sourceFile.isInnerClass(sourceFile.getPackageName() + simpleClassName))
                {
                    new File(parentDir,fileName).delete();
                }
            }
        }
    }              
}
