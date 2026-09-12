package doom.ai;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CampaignPruningTest {
    private CampaignPruning.Candidate candidate(String id,String parent,int health,int armor,int tics) {
        return new CampaignPruning.Candidate(id,parent,new SuccessPool.Entry(id+".lmp",1,tics,id,health,armor));
    }
    private List<CampaignPruning.Candidate> candidates() {
        return List.of(candidate("a-1","a",10,200,100),candidate("a-2","a",80,0,300),candidate("a-3","a",60,0,200),
                candidate("b-1","b",100,0,400),candidate("b-2","b",90,0,500),candidate("b-3","b",20,100,600));
    }
    @Test void parentScopeDropsTheLowestHealthSuccessFromEachGroupOfThree() {
        var p = new CampaignPruning.Policy(3,2,CampaignPruning.Scope.PARENT);
        assertEquals(Set.of("a-2","a-3","b-1","b-2"),p.retain(candidates(),3));
        assertEquals(p.retain(candidates(),3),p.retain(candidates(),6));
        assertEquals(6,p.retain(candidates(),2).size());
        assertEquals(6,p.retain(candidates(),4).size());
    }
    @Test void frontierScopeRetainsTwoAcrossAllParents() {
        var p = new CampaignPruning.Policy(3,2,CampaignPruning.Scope.FRONTIER);
        assertEquals(Set.of("b-1","b-2"),p.retain(candidates(),3));
    }
    @Test void tiesUseArmorThenTimeThenStableIdIndependentOfInputOrder() {
        var p = new CampaignPruning.Policy(3,2,CampaignPruning.Scope.PARENT);
        var input = new ArrayList<>(List.of(candidate("c","a",50,50,200),candidate("b","a",50,50,100),
                candidate("a","a",50,50,100),candidate("d","a",50,0,50)));
        assertEquals(Set.of("a","b"),p.retain(input,3));
        Collections.reverse(input); assertEquals(Set.of("a","b"),p.retain(input,3));
    }
    @Test void campaignOptionsMakePruningAndNormalExitsExplicit() {
        var options=CampaignTrainer.parse(new String[]{"--wad","doom.wad","--through","E1M8","--normal-exits",
                "--prune-every","3","--keep","2","--prune-scope","frontier"});
        assertEquals(CampaignPruning.Scope.FRONTIER,options.pruning().scope());
        assertTrue(options.training().normalExits());
        assertFalse(CampaignTrainer.parse(new String[]{"--wad","doom.wad"}).pruning().boundary(3));
        int saved=doom.system.GGame.secretexit;
        try {
            doom.system.GGame.secretexit=1; assertFalse(options.training().acceptsCurrentExit());
            doom.system.GGame.secretexit=0; assertTrue(options.training().acceptsCurrentExit());
        } finally { doom.system.GGame.secretexit=saved; }
        assertThrows(IllegalArgumentException.class,()->CampaignTrainer.parse(new String[]{"--wad","doom.wad",
                "--branches","2","--prune-every","3","--keep","2"}));
    }

    @Test void disabledPruningKeepsEverySuccessAndInvalidPoliciesAreRejected() {
        assertEquals(6,new CampaignPruning.Policy(0,2,CampaignPruning.Scope.PARENT).retain(candidates(),3).size());
        assertThrows(IllegalArgumentException.class,()->new CampaignPruning.Policy(-1,2,CampaignPruning.Scope.PARENT));
        assertThrows(IllegalArgumentException.class,()->new CampaignPruning.Policy(3,0,CampaignPruning.Scope.PARENT));
    }
}
