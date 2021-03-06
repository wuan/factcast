package org.factcast.server.rest;

import org.factcast.server.rest.resources.cache.CachableFilter;
import org.factcast.server.rest.resources.cache.NoCacheFilter;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.springframework.stereotype.Component;

import com.mercateo.common.rest.schemagen.link.injection.LinkFactoryResourceConfig;

//TODO do not rely on component scan
@Component
public class FactCastRestApplication extends ResourceConfig {

	public FactCastRestApplication() {
		register(JacksonFeature.class);
		// Register resources and providers using package-scanning.
		final String resourceBasePackage = "org.factcast.server.rest.resources";
		packages(resourceBasePackage);
		LinkFactoryResourceConfig.configure(this);
		register(NoCacheFilter.class);
		register(CachableFilter.class);
	}

}
