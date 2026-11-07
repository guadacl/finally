package com.finallyapp.kanban;

import com.finallyapp.api.KanbanApi;
import com.finallyapp.api.model.KanbanCard;
import com.finallyapp.api.model.KanbanCardCreate;
import com.finallyapp.api.model.KanbanCardUpdate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KanbanController implements KanbanApi {

    private final KanbanService service;

    public KanbanController(KanbanService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<List<KanbanCard>> getKanbanCards() {
        return ResponseEntity.ok(service.list());
    }

    @Override
    public ResponseEntity<KanbanCard> createKanbanCard(KanbanCardCreate req) {
        KanbanService.Upserted r = service.create(req);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.card());
    }

    @Override
    public ResponseEntity<KanbanCard> updateKanbanCard(UUID id, KanbanCardUpdate req) {
        return ResponseEntity.ok(service.update(id, req));
    }

    @Override
    public ResponseEntity<Void> deleteKanbanCard(UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
