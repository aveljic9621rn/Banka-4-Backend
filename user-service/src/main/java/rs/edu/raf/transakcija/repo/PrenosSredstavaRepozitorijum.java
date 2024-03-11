package rs.edu.raf.transakcija.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import rs.edu.raf.transakcija.model.PrenosSredstava;
import rs.edu.raf.transakcija.model.Status;

import java.util.List;

@Repository
public interface PrenosSredstavaRepozitorijum extends JpaRepository<PrenosSredstava, Long> {

    List<PrenosSredstava> findAllByStatus(Status status);

    List<PrenosSredstava> findAllByRacunPosiljaoca(Long racunPosiljaoca);

    List<PrenosSredstava> findAllByRacunPrimaoca(Long racunPrimaoca);

}
