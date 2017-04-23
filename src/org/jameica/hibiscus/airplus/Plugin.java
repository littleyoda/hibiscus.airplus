package org.jameica.hibiscus.airplus;

import java.net.MalformedURLException;
import java.net.URL;

import de.willuhn.jameica.messaging.TextMessage;
import de.willuhn.jameica.plugin.AbstractPlugin;
import de.willuhn.jameica.services.RepositoryService;
import de.willuhn.jameica.system.Application;
import de.willuhn.util.ApplicationException;

/**
 * Basis-Klasse des Plugins.
 */
public class Plugin extends AbstractPlugin
{




	@Override
	public void init() throws ApplicationException
	{
		super.init();
		try {
			URL oldRepo = new URL("http://www.open4me.de/hibiscus/");
			URL oldRepo2 = new URL("http://www.open4me.de/hibiscus");
			URL newRepo = new URL("https://www.open4me.de/hibiscus/");

			RepositoryService s = Application.getBootLoader().getBootable(RepositoryService.class);

			if (s.getRepositories().contains(oldRepo) || s.getRepositories().contains(oldRepo2)) {
				s.removeRepository(oldRepo);
				s.removeRepository(oldRepo2);
				s.addRepository(newRepo);

				TextMessage popup = new TextMessage();
				popup.setTitle("Repository aktualisiert");
				popup.setText("Das Plugin-Repository " + oldRepo + " wurde automatisch zur Erhöhung der Sicherheit auf https umgestellt!"); 
				Application.getMessagingFactory().getMessagingQueue("jameica.popup").sendMessage(popup);
			}
		} catch (MalformedURLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}


