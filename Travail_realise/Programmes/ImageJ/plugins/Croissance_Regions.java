package RegionGrow.main; // ATTENTION : � enlever si l'on veux utiliser le plugin depuis l'interface ImageJ
import static RegionGrow.main.Constantes.BLANC;
import static RegionGrow.main.Constantes.MARQUE;
import static RegionGrow.main.Constantes.MUSCLES_A_ENLEVER;

import java.awt.Point;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

import RegionGrow.baseDeCas.BaseDeCas;
import RegionGrow.baseDeCas.Cas;
import RegionGrow.baseDeCas.Germe;
import RegionGrow.baseDeCas.Probleme;
import RegionGrow.baseDeCas.Traitement;
import RegionGrow.lecture.LectureFichier;
import RegionGrow.ontologieAnatomie.ObjetAnatomie;
import RegionGrow.ontologieAnatomie.TumeurRenale;
import RegionGrow.ontologieRelationsSpatiales.RelationSpatiale;
// Importation des paquets ImageJ necessaires. 
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.filter.RankFilters;
import ij.plugin.filter.UnsharpMask;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageConverter;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

/**
 * Classe de segmentation d'image 2D par croissance de r�gion, utilisant la biblioth�que ImageJ
 * Source ImageJ : (https://imagej.net)
 * La classe etant un "plugin" ImageJ, elle h�rtie de deux m�thodes : 
 * setup() et run() appel�es au lancement du programme
 * @author Thibault DELAVELLE
 *
 */
public class Croissance_Regions implements PlugInFilter {


	//image de base en entr�e
	protected ImageProcessor ip;
	//dimensions image
	protected int h,w;
	//nouvelle image dans laquelle on va dessiner la segmentation
	protected ImageProcessor ipr;
	//couleur de chaque pixel de l'image
	protected int[][] couleursPixels;

	
	//fonction appel�e automatiquement pas ImageJ au lancement du plugin
	public int setup(String arg, ImagePlus imp) {
		
		//L'image de base est automatiquement convertie en image 8-bit (pour obtenir une image en niveaux de gris)
		ImageConverter ic = new ImageConverter(imp);
		ic.convertToGray8();
		imp.updateAndDraw();
		return DOES_8G;
	}

	//fonction principale, qui lit et construit la base de cas, puis fait la segmentation (fonction appel�e automtiquement)
	public void run(ImageProcessor i){
		
		this.ip = i;
		
		//hauteur et largeur de l'image
		h=ip.getHeight();
		w=ip.getWidth();
		
		//lecture du fichier de la base de cas, et construction de la base
		LectureFichier l = new LectureFichier();
		BaseDeCas base = null;
		try {
			base = l.LectureFichierBaseEnLigne("BaseDeCasEnLigne.txt");
			BaseDeCas test = l.parserXML("BaseDeCas.xml");
			base = test;
			//System.out.println(base.toString());
			//IJ.log("////////////////Avant segmentation : ///////////////\n");
			//IJ.log(base.toString());
		} catch (IOException e) {
			e.printStackTrace();
		}

		//recuperation des caracteristiques images de l'entree
		ImageStatistics stats = ip.getStatistics();
		IJ.log("\nSTATISTIQUES IMAGE EN ENTREE : moyenne : "+stats.mean+" asymetrie : "+stats.skewness+" ecart-type : "+stats.stdDev+" kurtosis : "+stats.kurtosis+"\n");

		//les caracs nonImage sont en dur forcement
		Probleme pEntree = new Probleme(5,175,22,0,4,3,stats.mean,stats.skewness,stats.stdDev,stats.kurtosis);

		
		// RaPC !!! (basique pour essayer pour le moment
		//indice du numero de cas
		int indiceBase = getMeilleurProbleme(pEntree,base);
		Cas casRememore = base.getCas(indiceBase);
		
		ArrayList<Germe> lgermes = casRememore.getSolution().getGermesUtiles();
		ArrayList<Germe> lgermesInutiles = casRememore.getSolution().getGermesInutiles();
		
		//pretaitements
		doPretraitements(casRememore);
		
		//suppression des muscles
		if(MUSCLES_A_ENLEVER)ip = supprimerObjets(lgermesInutiles);
		
		//calcul de la position du germe de la tumeur
		lgermes.add(recupererGermeTumeur(casRememore));
		
		//TODO en r�alit� ici il faut appeler la m�thode avec pEntree et casRememore
		//int[][] boiteEnglobante = getBoiteEnglobante(casRememore.getProbleme(), casRememore);
		
		//segmentation
		segmentation(lgermes, true);
		
		
		/* 
		 * TODO ICI il faudra ajouter un truc du genre : 
		 * if(isSatifaisante(segmentation(legermes))) base = ajouterDansBaseDeCas(pEntree, base.getCas(indiceBase).getSolution());
		 * ecritureXML(base);
		 */
		
	}
	
	
	/**
	 * R�alise la segmentation par croissance de r�gions
	 * @param lgermes : la liste de germes
	 * @param affichage : pour activer/d�sactiver l'affichage du r�sultat
	 */
	public void segmentation(ArrayList<Germe> lgermes, boolean affichage){
		
		
		//affichage de l'image pretrait�e
		ImagePlus pretraitee = new ImagePlus("Pretaitee",ip);
		if(affichage){
			pretraitee.show();
			pretraitee.updateAndDraw();
		}
		//creation de la nouvelle image dans laquelle on va dessiner la segmentation
		ImageProcessor ipDT= new ColorProcessor(w,h);
		ImagePlus imageDT= new ImagePlus("Croissance Regions", ipDT);
		ipr = imageDT.getProcessor();
		
		//tableau des intensites en niveau de gris des pixels de l'image
		int[][] pixelsA = ip.getIntArray();

		//liste de correspondance germe (donc region) --> couleur, utile pour la fusion de deux regions
		HashMap<Point,Integer> couleursRegions = new HashMap<Point,Integer>();
		//stockage des couleurs des pixels de l'image, necessaire pour la fusion et pour la fermeture (dilatation et erosion)
		couleursPixels = new int[w][h];

		//pour la couleur de la region (couleur initiale n'a pas d'importance)
		int color = 255;

		//pour chaque germe
		for(int i = 0; i<lgermes.size(); i++){
			
			Germe g = lgermes.get(i);
			//recuperation des coordonees 2D du germe
			int xGerme = (int)g.getX();
			int yGerme = (int)g.getY();
			
			//seuils pour chaque germe
			int SeuilGlobal = g.getSeuilGlobal(); 
			int SeuilLocal = g.getSeuilLocal();
			
			//a�chaque region on associe une couleur aleatoire
			Random r = new Random();
			color = r.nextInt(BLANC);
			color = g.getCouleur();
			
			couleursRegions.put(new Point(xGerme, yGerme),color);
			g.setCouleur(color);

			//l'intensite moyennne de la region courante (necessite le nombre de pixels de la region pour la m.a.j.)
			double moyenneRegion = pixelsA[xGerme][yGerme];
			int nbPixelsRegion = 0;

			//liste des points a evaluer, utilisee comme une pile (LIFO)
			ArrayList<Point> liste = new ArrayList<Point>();

			//ajout initial du germe pour la premiere iteration
			liste.add(new Point(xGerme,yGerme));
			ipr.putPixel(xGerme,yGerme,color);

			//tant qu'on a des pixels a evaluer (i.e. des pixels potentiellement de la meme region)
			while(!liste.isEmpty()){

				//recuperation du point courant
				Point courant = liste.get(0);
				int xCourant = (int)courant.getX();
				int yCourant = (int)courant.getY();
				//IJ.log("Val courant : "+pixelsA[xCourant][yCourant]+" | Val Germe : "+pixelsA[xGerme][yGerme]+" | Val Precedent : "+pixelsA[xPrecedent][yPrecedent]);
					//si le pixel est deja� marque (i.e. visite) on passe au pixel suivant
				if(pixelsA[xCourant][yCourant] == MARQUE){
					liste.remove(0);
					continue;
				}

				//pour chaque voisin en 4-connexite (non visite) du pixel courant, on va tester son homogeneite, et l'ajouter ou non a la region
				//droite
				if(xCourant<w-1 && pixelsA[xCourant+1][yCourant] != MARQUE){
					if(Math.abs(pixelsA[xCourant+1][yCourant]-moyenneRegion)<SeuilGlobal && Math.abs(pixelsA[xCourant][yCourant]-pixelsA[xCourant+1][yCourant])<=SeuilLocal)	{
						liste.add(new Point(xCourant+1,yCourant));
						ipr.putPixel(xCourant+1,yCourant,color);
						//mise a jour de la moyenne de la region
						couleursPixels[xCourant+1][yCourant] = color;
						moyenneRegion = ((moyenneRegion*nbPixelsRegion)+pixelsA[xCourant+1][yCourant])/(nbPixelsRegion+1);
						nbPixelsRegion++;
					}
				}

				//bas
				if(yCourant<h-1 && pixelsA[xCourant][yCourant+1] != MARQUE){
					if(Math.abs(pixelsA[xCourant][yCourant+1]-moyenneRegion)<SeuilGlobal && Math.abs(pixelsA[xCourant][yCourant]-pixelsA[xCourant][yCourant+1])<=SeuilLocal)	{
						liste.add(new Point(xCourant,yCourant+1));
						ipr.putPixel(xCourant,yCourant+1,color);
						couleursPixels[xCourant][yCourant+1] = color;
						moyenneRegion = ((moyenneRegion*nbPixelsRegion)+pixelsA[xCourant][yCourant+1])/(nbPixelsRegion+1);
						nbPixelsRegion++;
					}
				}

				//gauche
				if(xCourant>0  && pixelsA[xCourant-1][yCourant] != MARQUE){
					if(Math.abs(pixelsA[xCourant-1][yCourant]-moyenneRegion)<SeuilGlobal && Math.abs(pixelsA[xCourant][yCourant]-pixelsA[xCourant-1][yCourant])<=SeuilLocal)	{
						liste.add(new Point(xCourant-1,yCourant));
						ipr.putPixel(xCourant-1,yCourant,color);
						couleursPixels[xCourant-1][yCourant] = color;
						moyenneRegion = ((moyenneRegion*nbPixelsRegion)+pixelsA[xCourant-1][yCourant])/(nbPixelsRegion+1);
						nbPixelsRegion++;
					}
				}

				//haut
				if(yCourant>0  && pixelsA[xCourant][yCourant-1] != MARQUE){
					if(Math.abs(pixelsA[xCourant][yCourant-1]-moyenneRegion)<SeuilGlobal && Math.abs(pixelsA[xCourant][yCourant]-pixelsA[xCourant][yCourant-1])<=SeuilLocal)	{
						liste.add(new Point(xCourant,yCourant-1));
						ipr.putPixel(xCourant,yCourant-1,color);
						couleursPixels[xCourant][yCourant-1] = color;
						moyenneRegion = ((moyenneRegion*nbPixelsRegion)+pixelsA[xCourant][yCourant-1])/(nbPixelsRegion+1);
						nbPixelsRegion++;
					}
				}

				//marquage du point courant de la liste
				pixelsA[xCourant][yCourant] = MARQUE;

				//suppression du point courant de la liste
				liste.remove(0);

				//affichage du resultat en cours
				if(affichage){
					imageDT.show();
					imageDT.updateAndDraw();
				}

			}
			//temporisateur pour l'affichage, entre chaque affichage de region
			/*try {
				Thread.sleep(250);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}*/
		}

		//fusionRegions(new Point(154,200), new Point(113,213), couleursRegions);
		fermeture();
		
		if(affichage){
			imageDT.show();
			imageDT.updateAndDraw();
		}
		//reecriture de la base de cas dans le fichier TXT
		/*try {
			l.ecritureFichierBaseEnLigne("BaseDeCasEnLigne.txt", base);
		} catch (IOException e) {
			e.printStackTrace();
		}*/
	}
	
	/**
	 * R�alise une segmentation de l'image en placant des germes sur les muscles, puis les supprime
	 * @param base
	 * @param indiceBase
	 * @param l
	 * @return l'image de base sans les muscles
	 */
	public ImageProcessor supprimerObjets(ArrayList<Germe> lgermes){
		//on fait une segmentation classique, il faut que les germes soient aux positions des muscles
		segmentation(lgermes, true);
		
		//on cr�e une nouvelle image, qui sera une copie de celle de base, mais sans les muscles
		ImageProcessor ipMuscle= new ByteProcessor(w,h);
		ImagePlus imageMuscles= new ImagePlus("Suppression muscles", ipMuscle);
		ImageProcessor iprM = imageMuscles.getProcessor();
		int[][] pixelsM = ip.getIntArray();
		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				//pour savoir si un pixel fait partie d'un muscle il suffit de regarder si dans couleursPixels on a une valeur >0
				if(!(couleursPixels[i][j]>0))iprM.putPixel(i, j, pixelsM[i][j]); 
			}
		}
		imageMuscles.show();
		imageMuscles.updateAndDraw();
		return ipMuscle;
	}


	
	/**
	 * Calcul de similarite entre le probleme courrant et ceux de la base, pour retourner l'indice du plus similaire
	 * Pour le moment on fait une distance de Manhattan sans ponderation, donc pas tres optimis�e 
	 * @param p : le Probl�me � comparer
	 * @param base : la base de cas
	 * @return le meilleur probl�me = le plus similaire
	 */
	public int getMeilleurProbleme(Probleme p, BaseDeCas base){
		double best = Integer.MAX_VALUE;
		int indice = 0;

		//somme des carcteristiques du probleme d'entree
		double valEntree = p.getSommeCaracs(); 
		for(int i = 0 ; i < base.getTailleBase(); i++){
			double distance = 0;
			Probleme tmp = base.getCas(i).getProbleme();
			//somme des caracteristiques du probleme courant de la base
			double valTmp = tmp.getSommeCaracs();
			//calcul de distance entre les deux problemes
			distance = Math.abs(valTmp - valEntree);
			IJ.log("DISTANCE avec cas"+(i+1)+" : "+distance);
			if(distance<best){
				best = distance;
				indice = i;
			}
		}
		IJ.log("MEILLEURE SIMILARITE : "+best+" --> INDICE CAS : "+(indice+1));
		return indice;
	}



	/**
	 * R�alise la fusion de deux r�gions, i.e. leur attribue la m�me couleur de segmentation
	 * TODO : on pourrait la modifier pour comparer l'objetAnatomique et non plus la couleur
	 * @param germe1 : le germe de l'objet 1
	 * @param germe2 : le germe de l'objet 2
	 * @param couleursRegions : la couleur � donner � la nouvelle r�gion (fusion des deux)
	 */
	public void fusionRegions(Point germe1, Point germe2, HashMap<Point, Integer> couleursRegions){
		int color1 = couleursRegions.get(germe1);
		int color2 = couleursRegions.get(germe2);
		//la couleur de fusion est la moyenne des couleurs de chaque region
		int colorFusion = (color1+color2)/2;

		for(int i = 0; i<w; i++){
			for(int j = 0; j<h; j++){
				if(couleursPixels[i][j]==color1 || couleursPixels[i][j]==color2){
					ipr.putPixel(i,j,colorFusion);
					couleursPixels[i][j] = colorFusion;
				}			
			}
		}
	}



	/**
	 * R�alise une dilatation morphologique de l'image
	 */
	public void dilatation(){
		//int[][] masque = {{0,1,0},{1,1,1},{0,1,0}};
		int[][] masque = {{0,0,1,0,0},{0,1,1,1,0},{1,1,1,1,1},{0,1,1,1,0},{0,0,1,0,0}};
		int mSize = masque.length/2;
		//besoin d'une copie sinon si on modifie directement le vrai tableau en va colorier toute l'image
		int[][] tmp = new int[w][h];
		for(int i = 0 ; i < w ; i++){
			tmp[i] = couleursPixels[i].clone();
		}

		//pour chaque pixel de l'image
		for(int i=mSize; i<w-mSize;i++){
			for(int j=mSize; j<h-mSize; j++){
				int color = 0;
				boolean valide = false;
				//convolution
				for(int k=-mSize; k<=mSize; k++){
					for(int l=-mSize; l<=mSize; l++){
						if(masque[k+mSize][l+mSize]==1 && couleursPixels[i+k][j+l]>0){
							valide = true;
							color = couleursPixels[i+k][j+l];
						}
					}
				}
				//dessin et modification tableau
				if(valide && couleursPixels[i][j]==0){
					ipr.putPixel(i,j,color);
					tmp[i][j] = color;
				}
			}
		}

		//copiage dans le vrai tableau
		for(int i = 0 ; i < w ; i++){
			couleursPixels[i] = tmp[i].clone();
		}
	}

	/**
	 * R�alise une �rosion morphologique de l'image
	 */
	public void erosion(){
		//int[][] masque = {{0,1,0},{1,1,1},{0,1,0}};
		int[][] masque = {{0,0,1,0,0},{0,1,1,1,0},{1,1,1,1,1},{0,1,1,1,0},{0,0,1,0,0}};
		int mSize = masque.length/2;
		int[][] tmp = new int[w][h];
		for(int i = 0 ; i < w ; i++){
			tmp[i] = couleursPixels[i].clone();
		}
		for(int i=mSize; i<w-mSize;i++){
			for(int j=mSize; j<h-mSize; j++){
				boolean valide = true;
				for(int k=-mSize; k<=mSize; k++){
					for(int l=-mSize; l<=mSize; l++){
						if(masque[k+mSize][l+mSize]==1 && couleursPixels[i+k][j+l]==0)valide = false;
					}
				}
				if(!valide && couleursPixels[i][j]>0){
					ipr.putPixel(i,j,0);
					tmp[i][j] = 0;
				}
			}
		}
		for(int i = 0 ; i < w ; i++){
			couleursPixels[i] = tmp[i].clone();
		}

	}

	/**
	 * R�alise une fermeture morphologique qui correspond � : 
	 * Dilatation puis �rosion
	 */
	public void fermeture(){
		dilatation();
		erosion();
	}

	/**
	 * R�alise une ouverture morphologique qui correspond � :
	 * Erosion puis dilatation
	 */
	public void ouverture(){
		erosion();
		dilatation();
	}
	
	/**
	 * Applique tous les pr�traitements sur l'image de base par rapport aux traitements stock�s dans un cas
	 * @param cas : le cas contenant les traitements � appliquer
	 */
	public void doPretraitements(Cas cas){
		
		System.out.println("\n-----------------------------------");
		System.out.println("PRETRAITEMENTS avant segmentation : ");
		
		//pour chaque pretraitement
		for (int i = 0; i < cas.getSolution().getNbPreTraitements(); i++) {
			//r�cup�ration du traitement
			Traitement t = cas.getSolution().getPretraitement(i);
			//action d�pendante du type de traitement
			switch(t.getTypeTraitement()){
			case Unsharped:
				UnsharpMask mask = new UnsharpMask();
				FloatProcessor fp = null;
				//une image RGB dispose de trois canaux (R, G et B), il faut appliquer la mask sur chacun
				for(int canal = 0; canal < ip.getNChannels(); canal++){
					fp = ip.toFloat(canal, fp);
					fp.snapshot();
					//application du mask avec les param�tres stock�s dans le cas
					mask.sharpenFloat(fp, t.getRadius(), (float)t.getSeuil());
					ip.setPixels(canal,fp);
				}
				System.out.println("Unsharped Mask appliqu�");
				break;
			case Median:
				RankFilters rk = new RankFilters();
				//filtre m�dian avec le raidus du cas
				rk.rank(ip, t.getRadius(), RankFilters.MEDIAN);
				System.out.println("Filtre m�dian appliqu�");
				break;
			case Moyenneur:
				RankFilters rk2 = new RankFilters();
				//filtre moyenneur avec le raidus du cas
				rk2.rank(ip, t.getRadius(), RankFilters.MEAN);
				System.out.println("Filtre moyenneur appliqu�");
				break;
			default:
				System.err.println("ERREUR : TRAITEMENT "+t.getTypeTraitement()+" NON PRIS EN CHARGE");
				break;
			}
		}
	}
	
	/**
	 * Fait appel aux m�thodes de calcul de la position du germe de la tumeur :
	 * La classe GestionRelationsSpatiales
	 * @param casRememore : la cas rem�mor� de la base de cas
	 * @return le germe de la tumeur
	 */
	public Germe recupererGermeTumeur(Cas casRememore){
		GestionRelationsSpatiales gr = new GestionRelationsSpatiales(w,h);
		
		ArrayList<RelationSpatiale> lr = casRememore.getSolution().getPositionFloueTumeur();
		for(int indice = 0 ; indice < lr.size() ; indice++){
			Point ref = getCentreGravite(lr.get(indice).getReference(),casRememore.getSolution().getGermesUtiles(), casRememore.getSolution().getGermesInutiles());
			lr.get(indice).getReference().setPosition(ref);
		}
		Point p1 = gr.calculeGerme(lr);
		System.out.println("POSITION FLOUE DE LA TUMEUR : ("+p1.getX()+","+p1.getY()+")");
		//TODO les seuils du germe de la tumeur sont en dur
		Germe tumeur = new Germe((int)p1.getX(), (int)p1.getY(), 35, 20);
		tumeur.setLabelObjet(new TumeurRenale());
		tumeur.setColor();
		return tumeur;
	}

	
	/**
	 * Calcule le centre de gravit� de l'objet anatomique de r�f�rence pass� en param�tre
	 * le calcul est bas� sur les moments g�om�triques, et requiert la position du centre de gravit� d'un objet identique
	 * dans la partie solution de la base de cas
	 * Une erreur est d�clench�e si aucun objet identique n'est trouv�
	 * On entend par identique deux objets de la m�me classe
	 * @param reference : l'objet anatomique de r�f�rence
	 * @param germesUtiles : la liste des germes utiles de la solution
	 * @param germesInutiles : la liste des germes inutiles de la solution
	 * @return le centre de gravit� de l'objet de r�f�rence (un Point)
	 */
	public Point getCentreGravite(ObjetAnatomie reference, ArrayList<Germe> germesUtiles, ArrayList<Germe> germesInutiles) {
		//Pour chaque germe utile, on va rechercher l'objet identique � l'objet de r�f�rence, et le segmenter
		//d�s qu'on a trouv� l'objet identique, on arr�te la boucle
		
		boolean trouve = false;
		for (int i = 0; i < germesUtiles.size(); i++) {
			Germe g = germesUtiles.get(i);
			if(g.getLabelObjet().getClass().getName().equals(reference.getClass().getName())){
				Germe ref = new Germe(g.getX(), g.getY(), 35,20);
				ArrayList<Germe> lgermes = new ArrayList<Germe>();
				lgermes.add(ref);
				segmentation(lgermes, false);
				trouve = true;
				break;
			}
		}
		
		//si on n'a pas trouv� d'objet identique, on cherche dans la liste des germes inutiles
		if(!trouve){
			for (int i = 0; i < germesInutiles.size(); i++) {
				Germe g = germesInutiles.get(i);
				if(g.getLabelObjet().getClass().getName().equals(reference.getClass().getName())){
					Germe ref = new Germe(g.getX(), g.getY(), 35,20);
					ArrayList<Germe> lgermes = new ArrayList<Germe>();
					lgermes.add(ref);
					segmentation(lgermes, false);
					trouve = true;
					break;
				}
			}
		}
		
		//si on n'a toujours pas trouv�, cela signifie que l'objet de r�f�rence n'est pas resens� dans la base de cas XML
		// donc il est impossible de le segmenter pour trouver son centre de gravit�, puisque l'on a aucun point de d�part
		if(!trouve){
			System.err.println("ERREUR CENTRE DE GRAVITE : aucun objet de r�f�rence ne correspond dans la liste des germes utiles/inutiles ");
			return null; 
		}
		
		//calcul du centre de gravit� de la forme segment�e gr�ce au tableau colorPixels
		//on utilise les moments g�om�triques pour le calcul :
		//mPQ = sommeXsommeY x^P * y^Q * f(x,y) avec f(x,y) = 1 si (x,y) appartient � la forme, 0 sinon
		//centre de gravit� = (m10/m00, m01/m00)
		int m10 = 0;
		int m01 = 0;
		int m00 = 0;
		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				if(couleursPixels[i][j]>0){
					m10 += i;
					m01 += j;
					m00 += 1;
				}
			}
		}
		Point centreGravite = new Point((m10/m00),(m01/m00));
		System.out.println("Centre de gravit� de l'objet de r�f�rence : "+reference.toString()+" : ("+centreGravite.getX()+","+centreGravite.getY()+")");
		return centreGravite;
	}
	
	
	/*TODO je ne vois pas comment coder cette m�thode, sachant que j'ai � ma disposition : 
	 * 	- nbCoupes, hauteurCoupe
	 * 	- diametreTumeur, hauteurTumeur
	 * 	- la position du germe de la tumeur calcul� d'apr�s la solution du casRememore
	 */
	public int[][] getBoiteEnglobante(Probleme pb, Cas casRememore){
		/*double diametre = pb.getDiametreTumeur();
		double hauteur = pb.getHauteurTumeur();
		Germe g = this.recupererGermeTumeur(casRememore);
		Point centreTumeur = new Point(g.getX(), g.getY());
		int[][] boiteEnglobante = new int[w][h];
		*/
		return null;
	}
	

}