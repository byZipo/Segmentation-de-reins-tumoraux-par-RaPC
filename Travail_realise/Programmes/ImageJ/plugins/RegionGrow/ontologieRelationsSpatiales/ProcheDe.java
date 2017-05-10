package RegionGrow.ontologieRelationsSpatiales;

import RegionGrow.main.Constantes.TypeRelation;
/**
 * Relation spatiale représentant la proximité entre deux objets
 * @author Thibault DELAVELLE
 *
 */
public class ProcheDe extends RelationDeDistance{
	
	public ProcheDe(){
		this.type=TypeRelation.ProcheDe;
	}

}
