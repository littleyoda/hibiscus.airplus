package org.jameica.hibiscus.airplus.impl;

import java.io.BufferedReader;
import java.io.StringReader;
import java.rmi.RemoteException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.annotation.Resource;

import org.jameica.hibiscus.airplus.AirPlusSynchronizeBackend;
import org.jameica.hibiscus.airplus.Plugin;
import org.jameica.hibiscus.airplus.Utils;
import org.jameica.hibiscus.airplus.interfaces.AirPlusSynchronizeJob;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.ThreadedRefreshHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlAnchor;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlImageInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlRadioButtonInput;

import de.willuhn.datasource.GenericIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.ImportMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobKontoauszug;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.I18N;

/**
 * Implementierung des Kontoauszugsabruf fuer AirPlus.
 * Von der passenden Job-Klasse ableiten, damit der Job gefunden wird.
 */
public class AirPlusSynchronizeJobKontoauszug extends SynchronizeJobKontoauszug implements AirPlusSynchronizeJob
{
	private final static I18N i18n = Application.getPluginLoader().getPlugin(Plugin.class).getResources().getI18N();

	@Resource
	private AirPlusSynchronizeBackend backend = null;

	private String[] pages = {"opArt2", "opArt1"};

	private DateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
	/**
	 * @see org.jameica.hibiscus.airplus.interfaces.AirPlusSynchronizeJob#execute()
	 */
	@Override
	public void execute() throws Exception
	{
        
		Konto konto = (Konto) this.getContext(CTX_ENTITY); // wurde von AirPlusSynchronizeJobProviderKontoauszug dort abgelegt

		Logger.info("Rufe Umsätze ab für " + backend.getName());

		////////////////
		String firmenname = konto.getMeta(AirPlusSynchronizeBackend.PROP_COMPANYNAME, "");
		String username = konto.getMeta(AirPlusSynchronizeBackend.PROP_USERNAME, null);
		String password = konto.getMeta(AirPlusSynchronizeBackend.PROP_PASSWORD, null);
		if (username == null || username.length() == 0)
			throw new ApplicationException(i18n.tr("Bitte geben Sie Ihren AirPlus-Benutzernamen in den Synchronisationsoptionen ein"));

		if (password == null || password.length() == 0)
			password = Application.getCallback().askPassword("AirPlus");



		List<Umsatz> fetched = doOneAccount(konto, username, password, firmenname);

		Date oldest = null;

		// Ermitteln des aeltesten abgerufenen Umsatzes, um den Bereich zu ermitteln,
		// gegen den wir aus der Datenbank abgleichen
		for (Umsatz umsatz:fetched)
		{
			if (oldest == null || umsatz.getDatum().before(oldest))
				oldest = umsatz.getDatum();
		}


		// Wir holen uns die Umsaetze seit dem letzen Abruf von der Datenbank
		GenericIterator existing = konto.getUmsaetze(oldest,null);
		for (Umsatz umsatz:fetched)
		{
			if (existing.contains(umsatz) != null)
				continue; // haben wir schon

			// Neuer Umsatz. Anlegen
			umsatz.store();

			// Per Messaging Bescheid geben, dass es einen neuen Umsatz gibt. Der wird dann sofort in der Liste angezeigt
			Application.getMessagingFactory().sendMessage(new ImportMessage(umsatz));
		}

		konto.store();

		// Und per Messaging Bescheid geben, dass das Konto einen neuen Saldo hat
		Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
	}

	public List<Umsatz> doOneAccount(Konto konto, String username, String password, String firmenname) throws Exception {
		List<Umsatz> umsaetze = new ArrayList<Umsatz>();

		final WebClient webClient = new WebClient(BrowserVersion.INTERNET_EXPLORER_8);
		webClient.setCssErrorHandler(new SilentCssErrorHandler());
		webClient.setRefreshHandler(new ThreadedRefreshHandler());

		// Login-Page und Login
		HtmlPage page = webClient.getPage("https://portal.airplus.com/airplus/?Language=de&Country=1U");
		//writePage(page, "Login");
		HtmlForm form = page.getForms().get(0);
		form.getInputByName("companyLoginname").setValueAttribute(firmenname);
		form.getInputByName("userLoginname").setValueAttribute(username);
		form.getInputByName("password").setValueAttribute(password);
		List<HtmlElement> submit = form.getElementsByAttribute("input", "type", "image");
		HtmlImageInput x = (HtmlImageInput) submit.get(0);
		page = (HtmlPage) x.click();
		//writePage(page, "NachLogin");
		// > Startseite > Credit Card Management > Online-Kartenkonto
		page = webClient.getPage("https://portal.airplus.com/transaction/transactionStart.do?TKN=1u561.16wa578&__w=1#selected");

		// Suche nach dem passenden Account
		HtmlAnchor link = null;
		for (HtmlAnchor ahref : Utils.getLinks(page)) {
			if (ahref.asText().replace(" ", "").contains(konto.getKundennummer())) {
				link = ahref;
			}
		}
		if (link == null) {
			throw new ApplicationException(i18n.tr("Keine Informationen für Kreditkarte '" + konto.getKundennummer() + "' gefunden!"));
		}
		// Neue Umsätze und Abgerechnete Umsätze fortlaufend abrufen
		for (int i = 0; i < 2; i++) {
			page = link.click();
			TextPage textpage = uebersichtsart(page, pages[i]);
			List<Umsatz> teilUmsaetze = handle(textpage, konto);
			if (i == 0) {
				setzeSaldo(teilUmsaetze, konto);
			}
			umsaetze.addAll(teilUmsaetze);
		}
		// Logout
		webClient.getPage("https://portal.airplus.com/logout.do?TKN=1u561.6fuojg");
		webClient.closeAllWindows();
		return umsaetze;
	}
	
	private void setzeSaldo(List<Umsatz> teilUmsaetze, Konto konto) throws RemoteException {
		double saldo = 0;
		for (Umsatz umsatz : teilUmsaetze) {
			saldo += umsatz.getBetrag();
		}
		konto.setSaldo(saldo);
	}

	private TextPage uebersichtsart(HtmlPage page, String elementID) throws Exception {
		HtmlRadioButtonInput rbutton = (HtmlRadioButtonInput) page.getElementById(elementID);
		page = rbutton.click();
		//writePage(page, "Value1");
		HtmlImageInput i = page.getElementByName("submit");
		page = (HtmlPage) i.click();
		//writePage(page,"Res1");
		page = ((HtmlAnchor) page.getElementById("export")).click();
		// Select the Export-Link
		//writePage(page,"Export");
		TextPage p = ((HtmlAnchor) page.getElementByName("export")).click();
		return p;
	}

	private List<Umsatz> handle(TextPage p, Konto konto) throws Exception {
		List<Umsatz> umsaetze = new ArrayList<Umsatz>();
		//BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), "ISO-8859-1"));
		System.out.println(p.getContent());
		BufferedReader in = new BufferedReader(new StringReader(p.getContent()));
		in.readLine();
		in.readLine();
		in.readLine();
		String s;
		while ((s = in.readLine()) != null) {
			String[] x = s.split(";");
//			System.out.println(Arrays.toString(x));
//			for (int i = 0; i < x.length; i++) {
//				System.out.println(i + ": " + x[i]);
//			}
			// Aufbau der CSV Datei

			//	altes Format 0 Rechnung;1 R.-Datum;2 R.-Pos.; 3 Kaufdatum;4 Buch.Datum;5 Leistungserbringer;
			// 6Leistungsbeschreibung;7VK-Währung;8VK-Betrag;9Soll/Haben;10 Kurs;11Abr-Währung;
			//12Abgerechnet;13Soll/Haben;14;15Auslandseinsatzentgelt Faktor;16Abr-Währung;
			// 17Auslandseinsatzentgelt Wert

			// Neues Format:
			// 0 Rechnung	1 R.-Datum	2 A.I.D.A. Transaktion	3 R.-Pos.	 4 Kaufdatum	 5 Buch.Datum	 6 Leistungserbringer	7 Leistungsbeschreibung	
			// 8VK-Währung	9 VK-Betrag	10 Soll/Haben	11 Kurs	 12 Abr-Währung	13 Abgerechnet	14 Soll/Haben	15	16 Auslandseinsatzentgelt  Faktor	17 Abr-Währung	18 Auslandseinsatzentgelt Wert
			// Warnung HEader stimmt nicht mit dem Inhalt überein (STAND 15.Feb. 2014)

			Umsatz newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
			newUmsatz.setKonto(konto);
			newUmsatz.setBetrag(x[15].equals("H")?Utils.string2float(x[14]):-Utils.string2float(x[14]));
			newUmsatz.setDatum(df.parse(x[6]));
			newUmsatz.setValuta(df.parse(x[5]));
			newUmsatz.setWeitereVerwendungszwecke(Utils.parse(x[7] + " " + x[8]));
			umsaetze.add(newUmsatz);
			
			// Sonderfall Auslandseinsatzentgelt
			if (x.length >= 20) {
			
				newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
				newUmsatz.setKonto(konto);
				newUmsatz.setBetrag(-Utils.string2float(x[19]));
				newUmsatz.setDatum(df.parse(x[6]));
				newUmsatz.setValuta(df.parse(x[5]));
				newUmsatz.setWeitereVerwendungszwecke(Utils.parse(x[7] + " " 
									+ x[8] + " "
									+ "Auslandseinsatzentgelt"));
				umsaetze.add(newUmsatz);
			}
			
		}
		return umsaetze;

	}


	
}


