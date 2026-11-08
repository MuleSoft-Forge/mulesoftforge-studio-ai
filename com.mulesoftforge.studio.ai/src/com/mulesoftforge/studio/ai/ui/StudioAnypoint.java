package com.mulesoftforge.studio.ai.ui;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.mulesoftforge.studio.ai.exchange.ExchangeSearch;

// The Anypoint Platform account Studio is signed in to (Preferences > Anypoint Studio > Authentication), read by
// reflection so the plugin still loads where org.mule.tooling.core differs. The token is only ever put in the
// Authorization header of an Exchange request: never in a prompt, the transcript, a hook or a log.
final class StudioAnypoint {
  private static final String BUNDLE = "org.mule.tooling.core";

  private StudioAnypoint() {}

  // null when Studio is not signed in, or its login API is not there.
  static ExchangeSearch.Login login() {
    try {
      Bundle bundle = Platform.getBundle(BUNDLE);
      if (bundle == null) {
        return null;
      }
      Class<?> utils = bundle.loadClass(BUNDLE + ".utils.CoreUtils");
      Object manager = utils.getMethod("getLoginManager").invoke(null);
      Object user = manager == null ? null : manager.getClass().getMethod("retrieveActiveAuthUser").invoke(manager);
      if (user == null) {
        return null;
      }
      String token = (String) user.getClass().getMethod("getToken").invoke(user);
      if (token == null || token.isBlank()) {
        return null;
      }
      String baseUrl = (String) user.getClass().getMethod("getBaseUrl").invoke(user);
      Object organization = user.getClass().getMethod("getOrganization").invoke(user);
      String id = organization == null ? null : (String) organization.getClass().getMethod("getId").invoke(organization);
      String name = organization == null ? null
          : (String) organization.getClass().getMethod("getName").invoke(organization);
      return new ExchangeSearch.Login(baseUrl, token, id, name);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      return null;
    }
  }
}
