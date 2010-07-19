/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.seam.persistence;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Alternative;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AnnotatedField;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.ProcessProducer;
import javax.enterprise.inject.spi.Producer;
import javax.enterprise.util.AnnotationLiteral;
import javax.inject.Qualifier;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.PersistenceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Support for managed persistence contexts in a Java SE environment or Servlet container.
 *
 * <p>Unlike with standard Java EE, the unitName attribute on {@link PersistenceContext} must
 * be provided if the persistence unit is assigned a name in persistence.xml. This
 * class supports multiple persistence units, but it does not permit multiple
 * producers for the same persistence unit (naturally).</p>
 * 
 * @author Gavin King
 * @author Dan Allen
 */
public class PersistenceContextExtension implements Extension
{
   private static final Logger log = LoggerFactory.getLogger(PersistenceContextExtension.class);

   private Map<String, Bean<EntityManagerFactory>> emfBeans = new HashMap<String, Bean<EntityManagerFactory>>();

   private Boolean bootstrapRequired;

   /**
    * For @PersistenceContext producer fields, make a bean for the EMF, then
    * wrap the producer CDI creates, to get the EM from the EMF bean we made
    * instead of trying to get it from the Java EE component environment.
    */
   void processProducer(@Observes ProcessProducer<?, EntityManager> pp, final BeanManager bm)
   {
      if (Boolean.FALSE.equals(bootstrapRequired))
      {
         return;
      }
      else if (bootstrapRequired == null)
      {
         if (isPersistenceContainerManaged())
         {
            bootstrapRequired = false;
            return;
         }
         else
         {
            bootstrapRequired = true;
            log.info("Java SE persistence bootstrap required");
         }
      }

      if (pp.getAnnotatedMember().isAnnotationPresent(PersistenceContext.class))
      {
         AnnotatedField<?> field = (AnnotatedField<?>) pp.getAnnotatedMember();
         final String unitName = field.getAnnotation(PersistenceContext.class).unitName();
         if (!emfBeans.containsKey(unitName))
         {
            log.info("Found persistence context producer for persistence unit: " + unitName);
            final Class<?> module = field.getJavaMember().getDeclaringClass();
            final Set<Annotation> qualifiers = new HashSet<Annotation>();
            for (Annotation ann : field.getAnnotations())
            {
               Class<? extends Annotation> annotationType = ann.annotationType();
               // if ( bm.isQualifier(annotationType)) {
               if (annotationType.isAnnotationPresent(Qualifier.class))
               { // work around bug in Weld
                  qualifiers.add(ann);
               }
            }
            if (qualifiers.isEmpty())
            {
               qualifiers.add(new AnnotationLiteral<Default>()
               {
                  /** default value. Added only to suppress compiler warnings. */
                  private static final long serialVersionUID = 1L;
               });
            }
            qualifiers.add(new AnnotationLiteral<Any>()
            {
               /** default value. Added only to suppress compiler warnings. */
               private static final long serialVersionUID = 1L;
            });
            final boolean alternative = field.isAnnotationPresent(Alternative.class);
            final Set<Type> types = new HashSet<Type>()
            {
               /** default value. Added only to suppress compiler warnings. */
               private static final long serialVersionUID = 1L;
               {
                  add(EntityManagerFactory.class);
                  add(Object.class);
               }
            };

            // create and register a bean for the EMF
            emfBeans.put(unitName, new Bean<EntityManagerFactory>()
            {
               public Set<Type> getTypes()
               {
                  return types;
               }

               public Class<? extends Annotation> getScope()
               {
                  return ApplicationScoped.class;
               }

               public EntityManagerFactory create(CreationalContext<EntityManagerFactory> ctx)
               {
                  return Persistence.createEntityManagerFactory(unitName);
               }

               public void destroy(EntityManagerFactory emf, CreationalContext<EntityManagerFactory> ctx)
               {
                  emf.close();
                  ctx.release();
               }

               public Class<?> getBeanClass()
               {
                  return module;
               }

               public Set<InjectionPoint> getInjectionPoints()
               {
                  return Collections.emptySet();
               }

               public String getName()
               {
                  return null;
               }

               public Set<Annotation> getQualifiers()
               {
                  return qualifiers;
               }

               public Set<Class<? extends Annotation>> getStereotypes()
               {
                  return Collections.emptySet();

               }

               public boolean isAlternative()
               {
                  return alternative;
               }

               public boolean isNullable()
               {
                  return false;
               }
            });

         }
         else
         {
            throw new RuntimeException("There can only be one producer per persistence unit");
         }

         Producer<EntityManager> producer = new Producer<EntityManager>()
         {
            public Set<InjectionPoint> getInjectionPoints()
            {
               return Collections.emptySet();
            }

            public EntityManager produce(CreationalContext<EntityManager> ctx)
            {
               return getFactory(ctx).createEntityManager();
            }

            private EntityManagerFactory getFactory(CreationalContext<EntityManager> ctx)
            {
               return (EntityManagerFactory) bm.getReference(emfBeans.get(unitName), EntityManagerFactory.class, ctx);
            }

            public void dispose(EntityManager em)
            {
               if (em.isOpen()) // work around what I suspect is a bug in Weld
               {
                  em.close();
               }
            }
         };
         pp.setProducer(producer);
      }
   }

   /**
    * Register the EMF bean with the container.
    */
   void afterBeanDiscovery(@Observes AfterBeanDiscovery abd)
   {
      for (Bean<EntityManagerFactory> emfBean : emfBeans.values())
      {
         abd.addBean(emfBean);
      }
   }

   /**
    * Check whether persistence is container managed. For now, this simply
    * checks for the presence of the EJB API. If it's present, we assume
    * this is an EE environment and that persistence is container managed.
    */
   boolean isPersistenceContainerManaged() {
      boolean eeEnv = true;
      try
      {
         if (Thread.currentThread().getContextClassLoader() != null)
         {
            Thread.currentThread().getContextClassLoader().loadClass("javax.ejb.Stateless");
         }
         else
         {
            Class.forName("javax.ejb.Stateless");
         }
      }
      catch (ClassNotFoundException e)
      {
         eeEnv = false;
      }
      catch (NoClassDefFoundError e)
      {
         eeEnv = false;
      }

      return eeEnv;
   }
}